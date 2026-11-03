package io.legado.desktop.server

import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.CookieStore
import io.legado.desktop.server.db.BookRepo
import io.legado.desktop.server.db.BookmarkRepo
import io.legado.desktop.server.db.ChapterCacheRepo
import io.legado.desktop.server.db.CookieRepo
import io.legado.desktop.server.db.Database
import io.legado.desktop.server.db.ReplaceRepo
import io.legado.desktop.server.db.SettingsRepo
import io.legado.desktop.server.db.SourceRepo
import io.legado.desktop.server.image.CoverProxy
import io.legado.desktop.server.http.MediaProxy
import java.nio.file.Files
import java.nio.file.Path

class ServerContext(val dataDir: Path) {
    val database = Database(dataDir.resolve("legado.db"))
    val sourceRepo = SourceRepo(database)
    val bookRepo = BookRepo(database)
    val bookmarkRepo = BookmarkRepo(database)
    val chapterCacheRepo = ChapterCacheRepo(database)
    val replaceRepo = ReplaceRepo(database)
    val settingsRepo = SettingsRepo(database)
    val cookieRepo = CookieRepo(database)
    val coverProxy = CoverProxy(this, dataDir)
    val mediaProxy = MediaProxy(this)
    val previewStore = PreviewStore()

    init {
        // 恢复持久化 Cookie 后再注册监听，避免恢复过程重复写库
        cookieRepo.list().forEach { CookieStore.putRaw(it.key, it.cookie) }
        CookieStore.changeListener = { key, cookie ->
            try {
                if (cookie.isNullOrBlank()) cookieRepo.delete(key) else cookieRepo.upsert(key, cookie)
            } catch (_: Exception) {
            }
        }
    }

    fun findSource(bookUrl: String, sourceKey: String? = null): BookSource? {
        if (!sourceKey.isNullOrBlank()) return sourceRepo.get(sourceKey)
        // 同域多书源时取最长前缀，避免 http://host 抢先匹配 http://host/alt
        return sourceRepo.list()
            .filter { bookUrl.startsWith(it.bookSourceUrl) }
            .maxByOrNull { it.bookSourceUrl.length }
    }

    fun cacheStats(): CacheStats {
        val (coverBytes, coverFiles) = coverProxy.stats()
        val chapter = chapterCacheRepo.stats()
        return CacheStats(coverBytes, coverFiles, chapter.rows, chapter.bytes)
    }

    fun clearCache(type: String) {
        when (type) {
            "cover" -> coverProxy.clear()
            "chapter" -> chapterCacheRepo.clear()
            "all" -> {
                coverProxy.clear()
                chapterCacheRepo.clear()
            }
            else -> error("未知缓存类型: $type")
        }
    }

    fun stats(): Map<String, Any> {
        val databaseBytes = listOf("legado.db", "legado.db-wal", "legado.db-shm")
            .map { dataDir.resolve(it) }
            .filter { Files.exists(it) }
            .sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        return mapOf(
            "dataDir" to dataDir.toAbsolutePath().toString(),
            "databaseBytes" to databaseBytes,
            "bookCount" to bookRepo.count(),
            "sourceCount" to sourceRepo.count(),
            "replaceRuleCount" to replaceRepo.count(),
            "bookmarkCount" to bookmarkRepo.count(),
        )
    }
}

data class CacheStats(
    val coverBytes: Long,
    val coverFiles: Int,
    val chapterRows: Int,
    val chapterBytes: Long,
)

