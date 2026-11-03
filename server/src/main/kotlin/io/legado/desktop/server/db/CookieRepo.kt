package io.legado.desktop.server.db

data class CookieRow(
    val key: String,
    val cookie: String,
    val updatedAt: Long,
)

class CookieRepo(private val database: Database) {

    fun list(): List<CookieRow> = database.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT key, cookie, updated_at FROM cookies").use { rs ->
                val rows = ArrayList<CookieRow>()
                while (rs.next()) {
                    rows += CookieRow(rs.getString(1), rs.getString(2), rs.getLong(3))
                }
                rows
            }
        }
    }

    fun upsert(key: String, cookie: String) {
        database.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO cookies(key, cookie, updated_at) VALUES(?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET cookie = excluded.cookie, updated_at = excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, key)
                statement.setString(2, cookie)
                statement.setLong(3, System.currentTimeMillis())
                statement.executeUpdate()
            }
        }
    }

    fun delete(key: String) {
        database.use { connection ->
            connection.prepareStatement("DELETE FROM cookies WHERE key = ?").use { statement ->
                statement.setString(1, key)
                statement.executeUpdate()
            }
        }
    }
}
