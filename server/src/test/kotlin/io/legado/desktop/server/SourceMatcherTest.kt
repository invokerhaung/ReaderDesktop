package io.legado.desktop.server

import io.legado.app.data.entities.BookSource
import io.legado.desktop.server.api.SourceMatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SourceMatcherTest {

    @Test
    fun `matches by origin prefix and book url pattern`() {
        val context = ServerContext(Files.createTempDirectory("legado-match"))
        val repo = context.sourceRepo
        repo.upsert(BookSource(bookSourceUrl = "http://a.test", bookSourceName = "A"))
        repo.upsert(BookSource(bookSourceUrl = "http://a.test/sub", bookSourceName = "Sub"))
        repo.upsert(
            BookSource(
                bookSourceUrl = "http://c.test",
                bookSourceName = "Disabled",
                enabled = false,
            )
        )
        repo.upsert(
            BookSource(
                bookSourceUrl = "http://pattern-only.test",
                bookSourceName = "Pattern",
                bookUrlPattern = "https?://d\\.test/item/\\d+",
            )
        )
        val matcher = SourceMatcher(context)

        assertEquals(
            "http://a.test/sub",
            matcher.match("http://a.test/sub/book/1.html")!!.source.bookSourceUrl,
        )
        assertEquals(
            "http://a.test",
            matcher.match("http://a.test/book/1.html")!!.source.bookSourceUrl,
        )
        assertNull(matcher.match("http://c.test/x"))
        assertEquals(
            "http://pattern-only.test",
            matcher.match("http://d.test/item/42")!!.source.bookSourceUrl,
        )

        val option = matcher.match(
            "http://zzz.test/book/1,{\"origin\":\"http://a.test\"}"
        )!!
        assertEquals("http://a.test", option.source.bookSourceUrl)
        assertEquals("http://zzz.test/book/1", option.bookUrl)

        assertNull(matcher.match("not-a-url"))
    }
}
