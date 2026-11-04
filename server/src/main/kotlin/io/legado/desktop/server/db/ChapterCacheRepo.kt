package io.legado.desktop.server.db

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.model.ContentBlock
import io.legado.app.model.ContentDocument
import io.legado.app.utils.GSON

class ChapterCacheRepo(private val database: Database) {

    companion object {
        private const val VERSION = 1
    }

    fun get(bookUrl: String, index: Int, maxAgeDays: Int? = null): ContentDocument? =
        database.use { connection ->
            val ageClause = if (maxAgeDays != null) " AND updated_at >= ?" else ""
            connection.prepareStatement(
                "SELECT text, blocks FROM chapter_cache WHERE book_url = ? AND idx = ? AND version = ?$ageClause"
            ).use { statement ->
                statement.setString(1, bookUrl)
                statement.setInt(2, index)
                statement.setInt(3, VERSION)
                if (maxAgeDays != null) {
                    statement.setLong(
                        4,
                        System.currentTimeMillis() - maxAgeDays.coerceAtLeast(1) * 24L * 60 * 60 * 1000,
                    )
                }
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        val text = rs.getString("text") ?: ""
                        val blocks = parseBlocks(rs.getString("blocks"))
                            .ifEmpty { listOf(ContentBlock.Text(text)) }
                        ContentDocument(text, blocks)
                    }
                }
            }
        }

    fun save(bookUrl: String, index: Int, document: ContentDocument) {
        database.use { connection ->
            connection.prepareStatement(
                """
                INSERT OR REPLACE INTO chapter_cache(book_url, idx, version, text, blocks, updated_at)
                VALUES(?,?,?,?,?,?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, bookUrl)
                statement.setInt(2, index)
                statement.setInt(3, VERSION)
                statement.setString(4, document.text)
                statement.setString(5, GSON.toJson(document.blocks.map(::blockJson)))
                statement.setLong(6, System.currentTimeMillis())
                statement.executeUpdate()
            }
        }
    }

    fun indices(bookUrl: String): Set<Int> = database.use { connection ->
        connection.prepareStatement(
            "SELECT idx FROM chapter_cache WHERE book_url = ? AND version = ?"
        ).use { statement ->
            statement.setString(1, bookUrl)
            statement.setInt(2, VERSION)
            statement.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getInt("idx"))
                }
            }
        }
    }

    fun clear() {
        database.use { connection ->
            connection.prepareStatement("DELETE FROM chapter_cache").use { it.executeUpdate() }
        }
    }

    fun deleteByBook(bookUrl: String) {
        database.use { connection ->
            connection.prepareStatement("DELETE FROM chapter_cache WHERE book_url = ?").use { statement ->
                statement.setString(1, bookUrl)
                statement.executeUpdate()
            }
        }
    }

    fun prune(maxAgeDays: Int) {
        database.use { connection ->
            connection.prepareStatement("DELETE FROM chapter_cache WHERE updated_at < ?").use { statement ->
                statement.setLong(
                    1,
                    System.currentTimeMillis() - maxAgeDays.coerceAtLeast(1) * 24L * 60 * 60 * 1000,
                )
                statement.executeUpdate()
            }
        }
    }

    fun stats(): ChapterCacheStats = database.use { connection ->
        connection.prepareStatement(
            """
            SELECT COUNT(*),
                   COALESCE(SUM(LENGTH(CAST(text AS BLOB)) + LENGTH(CAST(blocks AS BLOB))), 0)
            FROM chapter_cache
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rs ->
                if (rs.next()) ChapterCacheStats(rs.getInt(1), rs.getLong(2))
                else ChapterCacheStats(0, 0)
            }
        }
    }

    private fun blockJson(block: ContentBlock): Map<String, Any?> = when (block) {
        is ContentBlock.Text -> mapOf("type" to "text", "text" to block.text)
        is ContentBlock.Image -> mapOf(
            "type" to "image",
            "src" to block.src,
            "alt" to block.alt,
            "style" to block.style,
        )
    }

    private fun parseBlocks(json: String?): List<ContentBlock> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
                val obj = element.asJsonObject
                when (obj.string("type")) {
                    "text" -> obj.string("text")?.let(ContentBlock::Text)
                    "image" -> obj.string("src")?.let { src ->
                        ContentBlock.Image(src, obj.string("alt"), obj.string("style"))
                    }
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.string(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asString }.getOrNull()
    }
}

data class ChapterCacheStats(val rows: Int, val bytes: Long)
