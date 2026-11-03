package io.legado.app.data.entities

data class Bookmark(
    var id: Long = 0L,
    var bookUrl: String = "",
    var chapterIndex: Int = 0,
    var chapterTitle: String = "",
    var mode: String = "paged",
    var position: Int = 0,
    var excerpt: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
)
