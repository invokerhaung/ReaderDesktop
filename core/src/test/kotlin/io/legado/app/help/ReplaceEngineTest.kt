package io.legado.app.help

import io.legado.app.data.entities.ReplaceRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplaceEngineTest {

    private val bookName = "长夜行"
    private val bookOrigin = "https://example.com"

    @Test
    fun `literal rules apply in sort order`() {
        val rules = listOf(
            ReplaceRule(name = "second", pattern = "甲", replacement = "乙", isRegex = false, order = 2),
            ReplaceRule(name = "first", pattern = "原文", replacement = "甲", isRegex = false, order = 1),
        )
        assertEquals(
            "乙",
            ReplaceEngine.applyContent("原文", bookName, bookOrigin, rules),
        )
    }

    @Test
    fun `regex replacement supports capture groups`() {
        val rules = listOf(
            ReplaceRule(name = "rename", pattern = "第(\\d+)章", replacement = "第\$1回"),
        )
        assertEquals(
            "第12回 雪夜",
            ReplaceEngine.applyContent("第12章 雪夜", bookName, bookOrigin, rules),
        )
    }

    @Test
    fun `content and title scopes are independent`() {
        val rules = listOf(
            ReplaceRule(name = "content", pattern = "广告", replacement = "", scopeContent = true),
            ReplaceRule(name = "title", pattern = "第", replacement = "卷", scopeTitle = true, scopeContent = false),
        )
        assertEquals("雪夜", ReplaceEngine.applyContent("雪夜广告", bookName, bookOrigin, rules))
        assertEquals("卷1章", ReplaceEngine.applyTitle("第1章", bookName, bookOrigin, rules))
        assertEquals("雪夜", ReplaceEngine.applyTitle("雪夜", bookName, bookOrigin, rules))
        assertEquals("第1章", ReplaceEngine.applyContent("第1章", bookName, bookOrigin, rules))
    }

    @Test
    fun `scope matches book name or origin and honors exclusion`() {
        val rule = ReplaceRule(
            name = "scoped",
            pattern = "x",
            replacement = "y",
            isRegex = false,
            scope = "长夜行,https://other.example",
        )
        assertEquals("y", ReplaceEngine.applyContent("x", bookName, bookOrigin, listOf(rule)))
        assertEquals("x", ReplaceEngine.applyContent("x", "另一本书", "https://elsewhere.example", listOf(rule)))

        val excluded = rule.copy(scope = null, excludeScope = "https://example.com")
        assertEquals("x", ReplaceEngine.applyContent("x", bookName, bookOrigin, listOf(excluded)))
    }

    @Test
    fun `disabled and invalid rules are ignored`() {
        val disabled = ReplaceRule(name = "off", pattern = "x", replacement = "y", isRegex = false, isEnabled = false)
        val invalid = ReplaceRule(name = "bad", pattern = "(", replacement = "y")
        assertEquals("x", ReplaceEngine.applyContent("x", bookName, bookOrigin, listOf(disabled, invalid)))
        assertFalse(ReplaceEngine.isValidPattern("("))
        assertTrue(ReplaceEngine.isValidPattern("第\\d+章"))
    }
}
