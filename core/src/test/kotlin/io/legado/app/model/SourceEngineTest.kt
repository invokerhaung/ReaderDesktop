package io.legado.app.model

import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class SourceEngineTest {

    @Test
    fun `search bookInfo chapters content`() = runBlocking {
        val server = FixtureServer()
        try {
            val source = source(server.baseUrl())
            val engine = SourceEngine(source)

            val books = engine.search("长夜")
            assertEquals(2, books.size)
            assertEquals("长夜行", books[0].name)
            assertEquals("沈砚", books[0].author)
            assertTrue(books[0].bookUrl.endsWith("/book/1"))
            assertTrue(server.lastRawQuery.contains("%E9%95%BF%E5%A4%9C"))

            val book = engine.bookInfo(books[0].bookUrl)
            assertEquals("长夜行", book.name)
            assertEquals("沈砚", book.author)
            assertTrue(book.intro?.contains("雪夜") == true)
            assertTrue(book.tocUrl.endsWith("/book/1/toc"))

            val chapters = engine.chapters(book)
            assertEquals(2, chapters.size)
            assertEquals("第 1 章 雪夜", chapters[0].title)
            assertTrue(chapters[0].url.endsWith("/book/1/ch1"))

            val content = engine.content(book, chapters[0])
            assertTrue(content.contains("第一章内容"))

            val document = engine.contentDocument(book, chapters[0])
            assertTrue(document.text.contains("第一章内容"))
            assertEquals(2, document.blocks.count { it is ContentBlock.Text })
            val image = document.blocks.filterIsInstance<ContentBlock.Image>().single()
            assertTrue(image.src.endsWith("/images/pic.png"))
            assertEquals("插图", image.alt)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `rhino js rule and css rule`() {
        val server = FixtureServer()
        try {
            val rule = AnalyzeRule(RuleData(), source(server.baseUrl()))
            rule.setContent("<html><body><h1>Hello</h1></body></html>")
            rule.setBaseUrl(server.baseUrl())
            assertEquals("Hello", rule.getString("tag.h1@text"))
            assertEquals(
                "900150983cd24fb0d6963f7d28e17f72",
                rule.getString("@js:java.md5Encode('abc')"),
            )
        } finally {
            server.stop()
        }
    }

    private fun source(baseUrl: String): BookSource =
        GSON.fromJsonObject<BookSource>(SOURCE_JSON.replace("BASE_URL", baseUrl))
            .getOrThrow()

    private class FixtureServer {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var lastRawQuery: String = ""
            private set

        init {
            server.createContext("/search") { exchange ->
                lastRawQuery = exchange.requestURI.rawQuery ?: ""
                respond(exchange, SEARCH_HTML)
            }
            server.createContext("/book/1/toc") { exchange -> respond(exchange, TOC_HTML) }
            server.createContext("/book/1/ch1") { exchange -> respond(exchange, CONTENT_HTML) }
            server.createContext("/book/1") { exchange -> respond(exchange, INFO_HTML) }
            server.start()
        }

        fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)

        private fun respond(exchange: com.sun.net.httpserver.HttpExchange, html: String) {
            val bytes = html.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        private const val SOURCE_JSON = """
        {
          "bookSourceUrl": "BASE_URL",
          "bookSourceName": "Fixture",
          "searchUrl": "BASE_URL/search?q={{key}}",
          "ruleSearch": {
            "bookList": "class.result@tag.li",
            "name": "tag.a@text",
            "author": "class.author@text",
            "bookUrl": "tag.a@href",
            "intro": "class.intro@text"
          },
          "ruleBookInfo": {
            "name": "tag.h1@text",
            "author": "class.author@text",
            "intro": "class.intro@text",
            "tocUrl": "class.toc@href"
          },
          "ruleToc": {
            "chapterList": "class.chapters@tag.li",
            "chapterName": "tag.a@text",
            "chapterUrl": "tag.a@href"
          },
          "ruleContent": {
            "content": "class.content@html"
          }
        }
        """

        private const val SEARCH_HTML = """
        <html><body>
          <ul class="result">
            <li><a href="/book/1">长夜行</a><span class="author">沈砚</span><p class="intro">雪夜与旧刀</p></li>
            <li><a href="/book/2">星海拾遗</a><span class="author">林渡</span><p class="intro">深空信号</p></li>
          </ul>
        </body></html>
        """

        private const val INFO_HTML = """
        <html><body>
          <h1>长夜行</h1>
          <span class="author">沈砚</span>
          <div class="intro">雪夜与旧刀的故事</div>
          <a class="toc" href="/book/1/toc">目录</a>
        </body></html>
        """

        private const val TOC_HTML = """
        <html><body>
          <ul class="chapters">
            <li><a href="/book/1/ch1">第 1 章 雪夜</a></li>
            <li><a href="/book/1/ch2">第 2 章 归人</a></li>
          </ul>
        </body></html>
        """

        private const val CONTENT_HTML = """
        <html><body>
          <div class="content">
            <p>第一章内容：雪落了一夜。</p>
            <img src="/images/pic.png" alt="插图" />
            <p>灯下坐着一个人。</p>
          </div>
        </body></html>
        """
    }
}
