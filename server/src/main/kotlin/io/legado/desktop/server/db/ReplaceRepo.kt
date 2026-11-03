package io.legado.desktop.server.db

import io.legado.app.data.entities.ReplaceRule

class ReplaceRepo(private val database: Database) {

    fun list(): List<ReplaceRule> = database.use { connection ->
        connection.prepareStatement(
            "SELECT * FROM replace_rules ORDER BY sort_order ASC, id ASC"
        ).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toReplaceRule())
                }
            }
        }
    }

    fun get(id: Long): ReplaceRule? = database.use { connection ->
        connection.prepareStatement("SELECT * FROM replace_rules WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toReplaceRule() else null
            }
        }
    }

    fun save(rule: ReplaceRule): ReplaceRule {
        val id = when {
            rule.id > 0 -> rule.id
            else -> nextId()
        }
        val saved = rule.copy(id = id)
        database.use { connection ->
            connection.prepareStatement(
                """
                INSERT OR REPLACE INTO replace_rules(
                    id, name, group_name, pattern, replacement, scope,
                    scope_title, scope_content, exclude_scope,
                    is_enabled, is_regex, timeout_ms, sort_order
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, saved.id)
                statement.setString(2, saved.name)
                statement.setString(3, saved.group)
                statement.setString(4, saved.pattern)
                statement.setString(5, saved.replacement)
                statement.setString(6, saved.scope)
                statement.setInt(7, if (saved.scopeTitle) 1 else 0)
                statement.setInt(8, if (saved.scopeContent) 1 else 0)
                statement.setString(9, saved.excludeScope)
                statement.setInt(10, if (saved.isEnabled) 1 else 0)
                statement.setInt(11, if (saved.isRegex) 1 else 0)
                statement.setLong(12, saved.timeoutMillisecond)
                statement.setInt(13, saved.order)
                statement.executeUpdate()
            }
        }
        return saved
    }

    fun delete(id: Long) {
        database.use { connection ->
            connection.prepareStatement("DELETE FROM replace_rules WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeUpdate()
            }
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        database.use { connection ->
            connection.prepareStatement(
                "UPDATE replace_rules SET is_enabled = ? WHERE id = ?"
            ).use { statement ->
                statement.setInt(1, if (enabled) 1 else 0)
                statement.setLong(2, id)
                statement.executeUpdate()
            }
        }
    }

    fun count(): Int = database.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM replace_rules").use { statement ->
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private fun nextId(): Long {
        var candidate = System.currentTimeMillis()
        while (get(candidate) != null) candidate++
        return candidate
    }

    private fun java.sql.ResultSet.toReplaceRule(): ReplaceRule = ReplaceRule(
        id = getLong("id"),
        name = getString("name") ?: "",
        group = getString("group_name"),
        pattern = getString("pattern") ?: "",
        replacement = getString("replacement") ?: "",
        scope = getString("scope"),
        scopeTitle = getInt("scope_title") == 1,
        scopeContent = getInt("scope_content") == 1,
        excludeScope = getString("exclude_scope"),
        isEnabled = getInt("is_enabled") == 1,
        isRegex = getInt("is_regex") == 1,
        timeoutMillisecond = getLong("timeout_ms"),
        order = getInt("sort_order"),
    )
}
