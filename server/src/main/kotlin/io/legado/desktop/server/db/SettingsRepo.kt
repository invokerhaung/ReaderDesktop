package io.legado.desktop.server.db

class SettingsRepo(private val database: Database) {

    fun all(): Map<String, String> = database.use { connection ->
        connection.prepareStatement("SELECT key, value FROM settings").use { statement ->
            statement.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString("key"), rs.getString("value"))
                }
            }
        }
    }

    fun get(key: String): String? = database.use { connection ->
        connection.prepareStatement("SELECT value FROM settings WHERE key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
        }
    }

    fun putAll(values: Map<String, String>) {
        database.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "INSERT OR REPLACE INTO settings(key, value) VALUES(?,?)"
                ).use { statement ->
                    values.forEach { (key, value) ->
                        if (key.isBlank()) return@forEach
                        statement.setString(1, key)
                        statement.setString(2, value)
                        statement.addBatch()
                    }
                    statement.executeBatch()
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
}
