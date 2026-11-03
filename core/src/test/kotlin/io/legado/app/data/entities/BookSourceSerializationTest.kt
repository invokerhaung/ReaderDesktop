package io.legado.app.data.entities

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BookSourceSerializationTest {

    @Test
    fun `headers and variables survive gson reload`() {
        val json = """
        {
          "bookSourceUrl": "https://example.com",
          "bookSourceName": "fixture",
          "header": "{\"Referer\":\"https://example.com/book/1\",\"User-Agent\":\"FixtureUA\"}",
          "variable": "{\"token\":\"abc\"}"
        }
        """.trimIndent()

        val source = GSON.fromJsonObject<BookSource>(json).getOrThrow()
        assertEquals("https://example.com/book/1", source.getHeaderMap()["Referer"])
        assertEquals("FixtureUA", source.getHeaderMap()["User-Agent"])
        assertEquals("abc", source.get("token"))
    }
}
