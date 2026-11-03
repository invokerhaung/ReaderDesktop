package io.legado.app.data.entities

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookSourceDeserializationTest {

    @Test
    fun `empty array rules are ignored`() {
        val json = """
            {"bookSourceName":"t","bookSourceUrl":"https://a.com","ruleExplore":[],"ruleSearch":[]}
        """.trimIndent()
        val source = GSON.fromJsonObject<BookSource>(json).getOrThrow()
        assertEquals("https://a.com", source.bookSourceUrl)
        assertNull(source.ruleExplore)
        assertNull(source.ruleSearch)
    }

    @Test
    fun `blank string rules are ignored`() {
        val json = """
            {"bookSourceName":"t","bookSourceUrl":"https://a.com","ruleToc":"","ruleContent":"  "}
        """.trimIndent()
        val source = GSON.fromJsonObject<BookSource>(json).getOrThrow()
        assertNull(source.ruleToc)
        assertNull(source.ruleContent)
    }

    @Test
    fun `object rules are parsed`() {
        val json = """
            {"bookSourceName":"t","bookSourceUrl":"https://a.com",
             "ruleSearch":{"bookList":"class.list","name":"tag.a@text"},
             "ruleContent":{"content":"@css:div#content@html"}}
        """.trimIndent()
        val source = GSON.fromJsonObject<BookSource>(json).getOrThrow()
        assertEquals("class.list", source.ruleSearch?.bookList)
        assertEquals("@css:div#content@html", source.ruleContent?.content)
    }

    @Test
    fun `primitive rule json strings are parsed`() {
        val json = """
            {"bookSourceName":"t","bookSourceUrl":"https://a.com",
             "ruleSearch":"{\"bookList\":\"class.list\"}"}
        """.trimIndent()
        val source = GSON.fromJsonObject<BookSource>(json).getOrThrow()
        assertNotNull(source.ruleSearch)
        assertEquals("class.list", source.ruleSearch?.bookList)
    }

    @Test
    fun `string fields tolerate objects and numbers`() {
        val json = """
            {"bookSourceName":123,"bookSourceUrl":"https://a.com","bookSourceGroup":[]}
        """.trimIndent()
        val source = GSON.fromJsonObject<BookSource>(json).getOrThrow()
        assertEquals("123", source.bookSourceName)
        assertEquals("[]", source.bookSourceGroup)
        assertTrue(source.enabled)
    }
}
