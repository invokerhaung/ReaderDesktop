package io.legado.desktop.server

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.desktop.server.db.BookRepo
import io.legado.desktop.server.db.Database
import io.legado.desktop.server.http.MediaProxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager

class Phase7Test {

    @Test
    fun `legacy database gains new columns`() {
        val dir = Files.createTempDirectory("legado-migrate")
        val file = dir.resolve("legado.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE books (
                        book_url TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        author TEXT,
                        origin TEXT,
                        origin_name TEXT,
                        cover_url TEXT,
                        intro TEXT,
                        kind TEXT,
                        latest_chapter TEXT,
                        toc_url TEXT,
                        total_chapters INTEGER NOT NULL DEFAULT 0,
                        dur_chapter_index INTEGER NOT NULL DEFAULT -1,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE chapters (
                        book_url TEXT NOT NULL,
                        idx INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        is_vip INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (book_url, idx)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    "INSERT INTO books(book_url, name, updated_at) VALUES('http://old/book', '旧书', 1)"
                )
            }
        }

        Database(file)
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            val bookColumns = columns(connection, "books")
            val chapterColumns = columns(connection, "chapters")
            assertTrue(bookColumns.contains("type"))
            assertTrue(bookColumns.contains("dur_chapter_pos"))
            assertTrue(chapterColumns.contains("resource_url"))
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT type, dur_chapter_pos FROM books WHERE book_url='http://old/book'")
                    .use { rs ->
                        assertTrue(rs.next())
                        assertEquals(8, rs.getInt("type"))
                        assertEquals(0L, rs.getLong("dur_chapter_pos"))
                    }
            }
        }
    }

    @Test
    fun `book type and position persist`() {
        val dir = Files.createTempDirectory("legado-repo")
        val context = ServerContext(dir)
        val repo = BookRepo(context.database)
        repo.upsert(
            Book(
                name = "漫画书",
                bookUrl = "http://x/comic",
                origin = "http://x",
                type = 64,
            )
        )
        repo.updateProgress("http://x/comic", 3, 1_234_567L)
        val loaded = repo.get("http://x/comic")!!
        assertEquals(64, loaded.type)
        assertEquals(3, loaded.durChapterIndex)
        assertEquals(1_234_567L, loaded.durChapterPos)

        repo.updateProgress("http://x/comic", 4)
        assertEquals(1_234_567L, repo.get("http://x/comic")!!.durChapterPos)
    }

    @Test
    fun `m3u8 rewrite proxies segments keys and variants`() {
        val dir = Files.createTempDirectory("legado-media")
        val context = ServerContext(dir)
        val media = MediaProxy(context)
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            https://cdn.example.com/a/seg1.ts?token=1
            #EXT-X-STREAM-INF:BANDWIDTH=1
            /abs/sub.m3u8
            #EXT-X-MAP:URI="init.mp4"
        """.trimIndent()
        val rewritten = media.rewriteM3u8(
            playlist,
            "https://cdn.example.com/path/index.m3u8",
            "https://source.example",
            "https://book.example/drama/1/",
        )
        assertTrue(rewritten.contains("/media?url=https%3A%2F%2Fcdn.example.com%2Fpath%2Fkey.bin"))
        assertTrue(rewritten.contains("/media?url=https%3A%2F%2Fcdn.example.com%2Fpath%2Fseg0.ts"))
        assertTrue(rewritten.contains("token%3D1"))
        assertTrue(rewritten.contains("/media?url=https%3A%2F%2Fcdn.example.com%2Fabs%2Fsub.m3u8"))
        assertTrue(rewritten.contains("/media?url=https%3A%2F%2Fcdn.example.com%2Fpath%2Finit.mp4"))
        assertTrue(rewritten.contains("source=https%3A%2F%2Fsource.example"))
        assertTrue(rewritten.contains("book=https%3A%2F%2Fbook.example%2Fdrama%2F1%2F"))
        assertTrue(rewritten.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"/media?url="))
    }

    @Test
    fun `source groups manage`() {
        val dir = Files.createTempDirectory("legado-groups")
        val context = ServerContext(dir)
        val repo = context.sourceRepo
        repo.upsert(BookSource(bookSourceUrl = "http://a", bookSourceName = "A", bookSourceGroup = "小说,精选"))
        repo.upsert(BookSource(bookSourceUrl = "http://b", bookSourceName = "B", bookSourceGroup = null))
        repo.upsert(BookSource(bookSourceUrl = "http://c", bookSourceName = "C", bookSourceGroup = "漫画"))

        assertEquals(
            setOf("小说", "精选", "漫画"),
            repo.groups().map { it.first }.toSet(),
        )
        assertEquals(1, repo.ungroupedCount())

        repo.renameGroup("小说", "文学")
        assertEquals(setOf("文学", "精选", "漫画"), repo.groups().map { it.first }.toSet())

        repo.renameGroup("精选", null)
        assertEquals(setOf("文学", "漫画"), repo.groups().map { it.first }.toSet())

        repo.setGroups("http://b", listOf("漫画", "文学"))
        assertEquals("漫画,文学", repo.get("http://b")!!.bookSourceGroup)
        assertEquals(0, repo.ungroupedCount())

        repo.setGroups("http://c", emptyList())
        assertEquals(1, repo.ungroupedCount())

        repo.assignGroupToUngrouped("未分组")
        assertEquals("未分组", repo.get("http://c")!!.bookSourceGroup)
        assertEquals(setOf("文学", "漫画", "未分组"), repo.groups().map { it.first }.toSet())
    }

    private fun columns(connection: java.sql.Connection, table: String): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getString("name"))
                }
            }
        }
}
