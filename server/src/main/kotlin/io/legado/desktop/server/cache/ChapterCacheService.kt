package io.legado.desktop.server.cache

import com.google.gson.JsonParser
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.SourceEngine
import io.legado.desktop.server.ServerContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 章节缓存任务：
 * - prefetch：阅读时自动预取当前章之后的 N 章，以及之后最多 5 章；只做隐藏 WebView，失败静默放弃。
 * - manual：目录里的手动范围缓存，允许可见 CF 验证，显示进度并可停止。
 */
class ChapterCacheService(private val context: ServerContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, CacheJob>()
    private val manualByBook = ConcurrentHashMap<String, CacheJob>()
    private val prefetchByBook = ConcurrentHashMap<String, CacheJob>()

    class CacheJob(
        val id: String,
        val bookUrl: String,
        val kind: String,
        val indices: List<Int>,
        val allowVisible: Boolean,
    ) {
        val total = AtomicInteger(0)
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val startedAt = System.currentTimeMillis()

        @Volatile
        var state = "running"

        @Volatile
        var currentIndex: Int? = null

        @Volatile
        var currentTitle: String? = null

        @Volatile
        var error: String? = null

        @Volatile
        var cancelled = false

        fun cancel() {
            cancelled = true
            if (state == "running") state = "cancelling"
        }

        fun toJson(): Map<String, Any?> = mapOf(
            "id" to id,
            "bookUrl" to bookUrl,
            "kind" to kind,
            "state" to state,
            "total" to total.get(),
            "done" to done.get(),
            "failed" to failed.get(),
            "currentIndex" to currentIndex,
            "currentTitle" to currentTitle,
            "error" to error,
            "cancelled" to cancelled,
            "allowVisible" to allowVisible,
        )
    }

    fun startManual(bookUrl: String, start: Int, end: Int): CacheJob {
        val book = context.bookRepo.get(bookUrl) ?: error("书籍不在书架")
        if ((book.type and BookType.text) == 0) error("仅支持文字书籍的章节缓存")
        val chapters = context.bookRepo.chapters(bookUrl)
        if (chapters.isEmpty()) error("目录为空")
        context.findSource(bookUrl) ?: error("找不到对应书源")
        if (start > end) error("缓存范围无效")
        val from = start.coerceIn(0, chapters.lastIndex)
        val to = end.coerceIn(from, chapters.lastIndex)
        pruneJobs()
        prefetchByBook.remove(bookUrl)?.cancel()
        manualByBook.remove(bookUrl)?.cancel()
        val job = CacheJob(
            id = UUID.randomUUID().toString(),
            bookUrl = bookUrl,
            kind = "manual",
            indices = (from..to).toList(),
            allowVisible = true,
        )
        jobs[job.id] = job
        manualByBook[bookUrl] = job
        scope.launch { run(job) }
        return job
    }

    fun startPrefetch(bookUrl: String, index: Int): CacheJob? {
        val count = preloadCount()
        if (count <= 0) {
            prefetchByBook.remove(bookUrl)?.cancel()
            return null
        }
        val manual = manualByBook[bookUrl]
        if (manual != null && manual.state == "running") return null
        val book = context.bookRepo.get(bookUrl) ?: return null
        if ((book.type and BookType.text) == 0) return null
        val chapters = context.bookRepo.chapters(bookUrl)
        if (chapters.isEmpty() || index !in chapters.indices) return null
        context.findSource(bookUrl) ?: return null

        val indices = LinkedHashSet<Int>()
        val forwardEnd = minOf(index + count, chapters.lastIndex)
        for (i in index + 1..forwardEnd) {
            if (!chapters[i].isVolume) indices.add(i)
        }
        val backwardStart = maxOf(0, index - minOf(5, count))
        for (i in index - 1 downTo backwardStart) {
            if (!chapters[i].isVolume) indices.add(i)
        }
        if (indices.isEmpty()) return null
        pruneJobs()
        prefetchByBook.remove(bookUrl)?.cancel()
        val job = CacheJob(
            id = UUID.randomUUID().toString(),
            bookUrl = bookUrl,
            kind = "prefetch",
            indices = indices.toList(),
            allowVisible = false,
        )
        jobs[job.id] = job
        prefetchByBook[bookUrl] = job
        scope.launch { run(job) }
        return job
    }

    fun stop(jobId: String): Map<String, Any?> {
        val job = jobs[jobId] ?: error("缓存任务不存在")
        job.cancel()
        return job.toJson()
    }

    fun status(jobId: String): Map<String, Any?> =
        (jobs[jobId] ?: error("缓存任务不存在")).toJson()

    /** 找某本书最近的活动任务，用于目录打开时恢复手动缓存进度。 */
    fun activeForBook(bookUrl: String): Map<String, Any?>? =
        manualByBook[bookUrl]?.takeIf { it.state == "running" || it.state == "cancelling" }?.toJson()

    fun cancelBook(bookUrl: String) {
        prefetchByBook.remove(bookUrl)?.cancel()
        manualByBook.remove(bookUrl)?.cancel()
    }

    fun cancelAll() {
        prefetchByBook.values.forEach { it.cancel() }
        manualByBook.values.forEach { it.cancel() }
        prefetchByBook.clear()
        manualByBook.clear()
    }

    private suspend fun run(job: CacheJob) {
        try {
            val book = context.bookRepo.get(job.bookUrl) ?: error("书籍不在书架")
            val source = context.findSource(job.bookUrl) ?: error("找不到对应书源")
            val chapters = context.bookRepo.chapters(job.bookUrl)
            val cached = context.chapterCacheRepo.indices(job.bookUrl).toMutableSet()
            val targets = job.indices.filter { index ->
                index in chapters.indices && !chapters[index].isVolume && index !in cached
            }
            job.total.set(targets.size)
            if (targets.isEmpty()) {
                job.state = if (job.cancelled) "cancelled" else "done"
                return
            }
            val parallelism = if (job.allowVisible) 1 else 2
            val semaphore = Semaphore(parallelism)
            coroutineScope {
                targets.map { index ->
                    async(Dispatchers.IO) {
                        if (job.cancelled) return@async
                        semaphore.withPermit {
                            if (job.cancelled) return@withPermit
                            val chapter = chapters[index]
                            job.currentIndex = index
                            job.currentTitle = chapter.title
                            if (fetchWithRetry(job, book, source, chapter)) {
                                synchronized(cached) { cached.add(index) }
                                job.done.incrementAndGet()
                            } else {
                                job.failed.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }
            job.state = when {
                job.cancelled -> "cancelled"
                job.failed.get() >= targets.size -> "error"
                else -> "done"
            }
        } catch (e: CancellationException) {
            job.state = "cancelled"
            throw e
        } catch (e: Exception) {
            job.state = "error"
            job.error = e.message ?: e.javaClass.simpleName
        } finally {
            job.currentIndex = null
            job.currentTitle = null
            manualByBook.remove(job.bookUrl, job)
            prefetchByBook.remove(job.bookUrl, job)
        }
    }

    private suspend fun fetchWithRetry(
        job: CacheJob,
        book: Book,
        source: BookSource,
        chapter: BookChapter,
    ): Boolean {
        val timeoutMs = if (job.allowVisible) 240_000L else 60_000L
        // 自动预取失败就跳过，避免 CF 站点把 7 章预取拖成数分钟；手动缓存保留 3 次重试
        val attempts = if (job.allowVisible) 3 else 1
        repeat(attempts) { attempt ->
            if (job.cancelled) return false
            try {
                val document = withTimeout(timeoutMs) {
                    SourceEngine(source).contentDocument(book, chapter, job.allowVisible)
                }
                if (!job.cancelled) {
                    context.chapterCacheRepo.save(job.bookUrl, chapter.index, document)
                }
                return true
            } catch (e: TimeoutCancellationException) {
                if (job.cancelled) return false
                delay(400L * (attempt + 1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (job.cancelled) return false
                delay(400L * (attempt + 1))
            }
        }
        return false
    }

    private fun preloadCount(): Int {
        val raw = context.settingsRepo.get("preloadChapterCount") ?: return 7
        return runCatching {
            JsonParser.parseString(raw).asInt.coerceIn(0, 50)
        }.getOrDefault(7)
    }

    private fun pruneJobs() {
        val cutoff = System.currentTimeMillis() - 6L * 60 * 60 * 1000
        jobs.entries.removeIf { (_, job) ->
            job.state != "running" && job.state != "cancelling" && job.startedAt < cutoff
        }
    }
}
