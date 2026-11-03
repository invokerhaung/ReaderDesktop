package io.legado.desktop.server.db

import io.legado.app.data.entities.Bookmark

class BookmarkRepo(private val database: Database) {

    fun list(bookUrl: String): List<Bookmark> = database.use { connection ->
        connection.prepareStatement(
            "SELECT * FROM bookmarks WHERE book_url = ? ORDER BY created_at DESC, id DESC"
        ).use { statement ->
            statement.setString(1, bookUrl)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toBookmark())
                }
            }
        }
    }

    fun add(bookmark: Bookmark): Bookmark = database.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO bookmarks(
                book_url, chapter_index, chapter_title, mode, position, excerpt, created_at
            ) VALUES(?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, bookmark.bookUrl)
            statement.setInt(2, bookmark.chapterIndex)
            statement.setString(3, bookmark.chapterTitle)
            statement.setString(4, bookmark.mode)
            statement.setInt(5, bookmark.position)
            statement.setString(6, bookmark.excerpt)
            statement.setLong(7, System.currentTimeMillis())
            statement.executeUpdate()
        }
        val id = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
        bookmark.copy(id = id)
    }

    fun delete(id: Long) {
        database.use { connection ->
            connection.prepareStatement("DELETE FROM bookmarks WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeUpdate()
            }
        }
    }

    fun count(): Int = database.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM bookmarks").use { statement ->
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun java.sql.ResultSet.toBookmark(): Bookmark = Bookmark(
        id = getLong("id"),
        bookUrl = getString("book_url"),
        chapterIndex = getInt("chapter_index"),
        chapterTitle = getString("chapter_title") ?: "",
        mode = getString("mode") ?: "paged",
        position = getInt("position"),
        excerpt = getString("excerpt"),
        createdAt = getLong("created_at"),
    )
}
