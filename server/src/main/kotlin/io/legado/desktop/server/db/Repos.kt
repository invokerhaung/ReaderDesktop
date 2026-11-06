package io.legado.desktop.server.db

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

class SourceRepo(private val database: Database) {

    fun list(): List<BookSource> = database.use { connection ->
        connection.prepareStatement("SELECT json FROM book_sources ORDER BY updated_at DESC").use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        GSON.fromJsonObject<BookSource>(rs.getString("json")).getOrNull()?.let(::add)
                    }
                }
            }
        }
    }

    fun enabled(): List<BookSource> = list().filter { it.enabled }

    fun get(key: String): BookSource? = database.use { connection ->
        connection.prepareStatement("SELECT json FROM book_sources WHERE source_key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rs ->
                if (rs.next()) GSON.fromJsonObject<BookSource>(rs.getString("json")).getOrNull() else null
            }
        }
    }

    fun upsert(source: BookSource) {
        database.use { connection ->
            connection.prepareStatement(
                "INSERT OR REPLACE INTO book_sources(source_key, name, group_name, enabled, json, updated_at) VALUES(?,?,?,?,?,?)"
            ).use { statement ->
                statement.setString(1, source.getKey())
                statement.setString(2, source.bookSourceName)
                statement.setString(3, source.bookSourceGroup)
                statement.setInt(4, if (source.enabled) 1 else 0)
                statement.setString(5, GSON.toJson(source))
                statement.setLong(6, System.currentTimeMillis())
                statement.executeUpdate()
            }
        }
    }

    fun delete(key: String) {
        database.use { connection ->
            connection.prepareStatement("DELETE FROM book_sources WHERE source_key = ?").use { statement ->
                statement.setString(1, key)
                statement.executeUpdate()
            }
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        get(key)?.let {
            it.enabled = enabled
            upsert(it)
        }
    }

    fun count(): Int = database.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM book_sources").use { statement ->
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun groups(): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        list().forEach { source ->
            parseGroups(source.bookSourceGroup).forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    fun ungroupedCount(): Int = list().count { parseGroups(it.bookSourceGroup).isEmpty() }

    fun setGroups(key: String, groups: List<String>) {
        val source = get(key) ?: return
        source.bookSourceGroup = normalizeGroups(groups).joinToString(",").ifBlank { null }
        upsert(source)
    }

    /** to 为空表示删除该分组。 */
    fun renameGroup(from: String, to: String?) {
        val oldName = from.trim()
        if (oldName.isEmpty()) return
        val newName = to?.trim()?.takeIf { it.isNotEmpty() }
        list().forEach { source ->
            val groups = parseGroups(source.bookSourceGroup)
            if (!groups.contains(oldName)) return@forEach
            val updated = groups.toMutableSet().apply {
                remove(oldName)
                newName?.let(::add)
            }
            source.bookSourceGroup = normalizeGroups(updated.toList()).joinToString(",").ifBlank { null }
            upsert(source)
        }
    }

    fun assignGroupToUngrouped(name: String) {
        val group = name.trim()
        if (group.isEmpty()) return
        list().forEach { source ->
            if (parseGroups(source.bookSourceGroup).isNotEmpty()) return@forEach
            source.bookSourceGroup = group
            upsert(source)
        }
    }

    private fun parseGroups(value: String?): List<String> =
        value?.split(Regex("[,，]"))
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun normalizeGroups(groups: List<String>): List<String> =
        groups.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

class BookRepo(private val database: Database) {

    fun list(): List<Book> = database.use { connection ->
        connection.prepareStatement("SELECT * FROM books ORDER BY updated_at DESC").use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toBook())
                }
            }
        }
    }

    fun get(bookUrl: String): Book? = database.use { connection ->
        connection.prepareStatement("SELECT * FROM books WHERE book_url = ?").use { statement ->
            statement.setString(1, bookUrl)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toBook() else null }
        }
    }

    fun findByNameAuthor(name: String, author: String): Book? = database.use { connection ->
        connection.prepareStatement(
            "SELECT * FROM books WHERE name = ? AND author = ? LIMIT 1"
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, author)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toBook() else null }
        }
    }

    fun upsert(book: Book) {
        database.use { connection ->
            connection.prepareStatement(
                """
                INSERT OR REPLACE INTO books(
                    book_url, name, author, origin, origin_name, cover_url, intro, kind,
                    latest_chapter, toc_url, total_chapters, dur_chapter_index, dur_chapter_pos, type, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, book.bookUrl)
                statement.setString(2, book.name)
                statement.setString(3, book.author)
                statement.setString(4, book.origin)
                statement.setString(5, book.originName)
                statement.setString(6, book.coverUrl)
                statement.setString(7, book.intro)
                statement.setString(8, book.kind)
                statement.setString(9, book.latestChapterTitle)
                statement.setString(10, book.tocUrl)
                statement.setInt(11, book.totalChapterNum)
                statement.setInt(12, book.durChapterIndex)
                statement.setLong(13, book.durChapterPos)
                statement.setInt(14, book.type)
                statement.setLong(15, System.currentTimeMillis())
                statement.executeUpdate()
            }
        }
    }

    fun delete(bookUrl: String) {
        database.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM chapters WHERE book_url = ?").use {
                    it.setString(1, bookUrl)
                    it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM bookmarks WHERE book_url = ?").use {
                    it.setString(1, bookUrl)
                    it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM chapter_cache WHERE book_url = ?").use {
                    it.setString(1, bookUrl)
                    it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM books WHERE book_url = ?").use {
                    it.setString(1, bookUrl)
                    it.executeUpdate()
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun updateProgress(bookUrl: String, index: Int, position: Long? = null) {
        database.use { connection ->
            if (position == null) {
                connection.prepareStatement(
                    "UPDATE books SET dur_chapter_index = ?, updated_at = ? WHERE book_url = ?"
                ).use { statement ->
                    statement.setInt(1, index)
                    statement.setLong(2, System.currentTimeMillis())
                    statement.setString(3, bookUrl)
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement(
                    "UPDATE books SET dur_chapter_index = ?, dur_chapter_pos = ?, updated_at = ? WHERE book_url = ?"
                ).use { statement ->
                    statement.setInt(1, index)
                    statement.setLong(2, position)
                    statement.setLong(3, System.currentTimeMillis())
                    statement.setString(4, bookUrl)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun count(): Int = database.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM books").use { statement ->
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun chapters(bookUrl: String): List<BookChapter> = database.use { connection ->
        connection.prepareStatement(
            "SELECT idx, title, url, is_vip, resource_url FROM chapters WHERE book_url = ? ORDER BY idx"
        ).use { statement ->
            statement.setString(1, bookUrl)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            BookChapter(
                                url = rs.getString("url"),
                                title = rs.getString("title"),
                                bookUrl = bookUrl,
                                index = rs.getInt("idx"),
                                isVip = rs.getInt("is_vip") == 1,
                                resourceUrl = rs.getString("resource_url"),
                            )
                        )
                    }
                }
            }
        }
    }

    fun getChapter(bookUrl: String, index: Int): BookChapter? = database.use { connection ->
        connection.prepareStatement(
            "SELECT idx, title, url, is_vip, resource_url FROM chapters WHERE book_url = ? AND idx = ?"
        ).use { statement ->
            statement.setString(1, bookUrl)
            statement.setInt(2, index)
            statement.executeQuery().use { rs ->
                if (rs.next()) BookChapter(
                    url = rs.getString("url"),
                    title = rs.getString("title"),
                    bookUrl = bookUrl,
                    index = rs.getInt("idx"),
                    isVip = rs.getInt("is_vip") == 1,
                    resourceUrl = rs.getString("resource_url"),
                ) else null
            }
        }
    }

    /** 目录可能插入新章节，进度以章节 URL 为准时用它找回正确下标。 */
    fun chapterIndexByUrl(bookUrl: String, chapterUrl: String): Int? = database.use { connection ->
        connection.prepareStatement(
            "SELECT idx FROM chapters WHERE book_url = ? AND url = ? LIMIT 1"
        ).use { statement ->
            statement.setString(1, bookUrl)
            statement.setString(2, chapterUrl)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        }
    }

    fun chapterUrlAt(bookUrl: String, index: Int): String? = database.use { connection ->
        connection.prepareStatement(
            "SELECT url FROM chapters WHERE book_url = ? AND idx = ?"
        ).use { statement ->
            statement.setString(1, bookUrl)
            statement.setInt(2, index)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun saveChapterResource(bookUrl: String, index: Int, resourceUrl: String) {
        database.use { connection ->
            connection.prepareStatement(
                "UPDATE chapters SET resource_url = ? WHERE book_url = ? AND idx = ?"
            ).use { statement ->
                statement.setString(1, resourceUrl)
                statement.setString(2, bookUrl)
                statement.setInt(3, index)
                statement.executeUpdate()
            }
        }
    }

    fun saveChapters(bookUrl: String, chapters: List<BookChapter>) {
        database.use { connection ->
            connection.autoCommit = false
            try {
                val previous = HashMap<String, Int>()
                connection.prepareStatement("SELECT idx, url FROM chapters WHERE book_url = ?").use { statement ->
                    statement.setString(1, bookUrl)
                    statement.executeQuery().use { rs ->
                        while (rs.next()) previous[rs.getString("url")] = rs.getInt("idx")
                    }
                }
                // 目录插入/删除会让 URL 对应下标变化，旧下标缓存的正文必须作废
                val mappingChanged = previous.size != chapters.size ||
                    chapters.any { previous[it.url] != it.index }
                connection.prepareStatement("DELETE FROM chapters WHERE book_url = ?").use { statement ->
                    statement.setString(1, bookUrl)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO chapters(book_url, idx, title, url, is_vip, resource_url) VALUES(?,?,?,?,?,?)"
                ).use { statement ->
                    chapters.forEach { chapter ->
                        statement.setString(1, bookUrl)
                        statement.setInt(2, chapter.index)
                        statement.setString(3, chapter.title)
                        statement.setString(4, chapter.url)
                        statement.setInt(5, if (chapter.isVip) 1 else 0)
                        statement.setString(6, chapter.resourceUrl)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                if (mappingChanged) {
                    connection.prepareStatement("DELETE FROM chapter_cache WHERE book_url = ?").use { statement ->
                        statement.setString(1, bookUrl)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun java.sql.ResultSet.toBook(): Book = Book(
        name = getString("name") ?: "",
        author = getString("author") ?: "",
        bookUrl = getString("book_url"),
        origin = getString("origin") ?: "",
        originName = getString("origin_name") ?: "",
        tocUrl = getString("toc_url") ?: "",
        coverUrl = getString("cover_url"),
        intro = getString("intro"),
        kind = getString("kind"),
        latestChapterTitle = getString("latest_chapter"),
        totalChapterNum = getInt("total_chapters"),
        durChapterIndex = getInt("dur_chapter_index"),
        durChapterPos = getLong("dur_chapter_pos"),
        type = getInt("type"),
    )
}
