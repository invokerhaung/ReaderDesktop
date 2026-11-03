package io.legado.desktop.server.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class Database(private val file: Path) {

    init {
        Class.forName("org.sqlite.JDBC")
        Files.createDirectories(file.toAbsolutePath().parent)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS book_sources (
                        source_key TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        group_name TEXT,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS books (
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
                        dur_chapter_pos INTEGER NOT NULL DEFAULT 0,
                        type INTEGER NOT NULL DEFAULT 8,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS chapters (
                        book_url TEXT NOT NULL,
                        idx INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        is_vip INTEGER NOT NULL DEFAULT 0,
                        resource_url TEXT,
                        PRIMARY KEY (book_url, idx)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS settings (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS replace_rules (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        group_name TEXT,
                        pattern TEXT NOT NULL,
                        replacement TEXT NOT NULL DEFAULT '',
                        scope TEXT,
                        scope_title INTEGER NOT NULL DEFAULT 0,
                        scope_content INTEGER NOT NULL DEFAULT 1,
                        exclude_scope TEXT,
                        is_enabled INTEGER NOT NULL DEFAULT 1,
                        is_regex INTEGER NOT NULL DEFAULT 1,
                        timeout_ms INTEGER NOT NULL DEFAULT 3000,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        book_url TEXT NOT NULL,
                        chapter_index INTEGER NOT NULL,
                        chapter_title TEXT NOT NULL,
                        mode TEXT NOT NULL DEFAULT 'paged',
                        position INTEGER NOT NULL DEFAULT 0,
                        excerpt TEXT,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS chapter_cache (
                        book_url TEXT NOT NULL,
                        idx INTEGER NOT NULL,
                        version INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        blocks TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (book_url, idx)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS cookies (
                        key TEXT PRIMARY KEY,
                        cookie TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_chapter_cache_updated ON chapter_cache(updated_at)"
                )
                ensureColumn(connection, "books", "dur_chapter_pos", "dur_chapter_pos INTEGER NOT NULL DEFAULT 0")
                ensureColumn(connection, "books", "type", "type INTEGER NOT NULL DEFAULT 8")
                ensureColumn(connection, "chapters", "resource_url", "resource_url TEXT")
            }
        }
    }

    private fun ensureColumn(connection: Connection, table: String, column: String, ddl: String) {
        val exists = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("name") == column) {
                        found = true
                        break
                    }
                }
                found
            }
        }
        if (!exists) {
            connection.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $ddl") }
        }
    }

    fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")

    fun <T> use(block: (Connection) -> T): T = connection().use(block)
}
