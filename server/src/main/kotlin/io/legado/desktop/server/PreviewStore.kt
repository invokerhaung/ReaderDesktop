package io.legado.desktop.server

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.ContentDocument
import io.legado.app.model.VideoSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 临时试看会话：与 Android 的 notShelf 行为一致，书籍与目录只存在内存里，不写书架。
 */
data class PreviewSession(
    val id: String,
    val sourceKey: String,
    val book: Book,
    val chapters: List<BookChapter>,
    val createdAt: Long,
    val contentCache: MutableMap<Int, ContentDocument> = ConcurrentHashMap(),
    val videoCache: MutableMap<Int, VideoSource> = ConcurrentHashMap(),
)

class PreviewStore {

    private val sessions = ConcurrentHashMap<String, PreviewSession>()
    private val ttlMillis = 30 * 60 * 1000L

    fun create(sourceKey: String, book: Book, chapters: List<BookChapter>): PreviewSession {
        prune()
        val id = UUID.randomUUID().toString().replace("-", "")
        val session = PreviewSession(id, sourceKey, book, chapters, System.currentTimeMillis())
        sessions[id] = session
        return session
    }

    fun get(id: String): PreviewSession? {
        prune()
        return sessions[id]
    }

    fun remove(id: String) {
        sessions.remove(id)
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.createdAt > ttlMillis }
    }
}
