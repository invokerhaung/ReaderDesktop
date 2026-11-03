package io.legado.desktop.server

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import io.legado.desktop.core.Engine
import io.legado.desktop.server.api.ApiRouter
import io.legado.desktop.server.http.DesktopHttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration

class ApiIntegrationTest {

    @Test
    fun `import source search shelf chapters content`() {
        val fixture = FixtureServer()
        val dataDir = Files.createTempDirectory("legado-api-test")
        val options = ServerOptions("127.0.0.1", 0, dataDir, dataDir, "test")
        val env = DesktopAppEnv(dataDir).apply { ensureDirs() }
        val context = ServerContext(dataDir)
        val server = DesktopHttpServer(
            options,
            Engine(env),
            ApiRouter(context),
            context.coverProxy,
            context.mediaProxy,
        )
        server.start(5_000, false)
        val port = server.listeningPort
        try {
            val sourceJson = SOURCE_JSON.replace("BASE_URL", fixture.baseUrl())
            val imported = post(port, "/api/sources/import", sourceJson)
            assertEquals(true, imported.get("isSuccess").asBoolean)
            assertEquals(1, imported.getAsJsonObject("data").get("imported").asInt)

            val sources = get(port, "/api/sources")
            assertEquals(1, sources.getAsJsonArray("data").size())
            assertEquals(
                "中文 Fixture",
                sources.getAsJsonArray("data")[0].asJsonObject.get("bookSourceName").asString,
            )

            val search = post(port, "/api/search", """{"keyword":"长夜"}""")
            assertEquals(2, search.getAsJsonArray("data").size())
            assertEquals(
                "长夜行",
                search.getAsJsonArray("data")[0].asJsonObject.get("name").asString,
            )

            val bookUrl = fixture.baseUrl() + "/book/1"
            val added = post(port, "/api/books", """{"bookUrl":"$bookUrl"}""")
            val book = added.getAsJsonObject("data")
            assertEquals("长夜行", book.get("name").asString)
            assertEquals(0, book.get("totalChapterNum").asInt)
            assertEquals(fixture.baseUrl() + "/cover.png", book.get("coverUrl").asString)

            val coverUrl = fixture.baseUrl() + "/cover.png"
            val image = getImage(
                port,
                "/img?url=${encode(coverUrl)}&source=${encode(fixture.baseUrl())}&book=${encode(bookUrl)}",
            )
            assertEquals(200, image.statusCode())
            assertEquals("image/png", image.headers().firstValue("Content-Type").orElse(""))
            assertTrue(image.body().contentEquals(DECODED_COVER_BYTES))
            assertTrue(Files.isDirectory(dataDir.resolve("cache/covers")))

            val contentImage = getImage(
                port,
                "/img?url=${encode(coverUrl)}&source=${encode(fixture.baseUrl())}&book=${encode(bookUrl)}&kind=content",
            )
            assertEquals(200, contentImage.statusCode())
            assertTrue(contentImage.body().contentEquals(DECODED_COVER_BYTES))

            val shelf = get(port, "/api/books")
            assertEquals(1, shelf.getAsJsonArray("data").size())

            val chapters = get(port, "/api/books/chapters?url=$bookUrl")
            assertEquals(2, chapters.getAsJsonArray("data").size())

            val content = get(port, "/api/books/content?url=$bookUrl&index=0")
            assertTrue(
                content.getAsJsonObject("data").get("content").asString.contains("第一章内容")
            )

            val progress = put(port, "/api/books/progress", """{"url":"$bookUrl","index":1}""")
            assertEquals(1, progress.getAsJsonObject("data").get("index").asInt)

            val bookmark = post(
                port,
                "/api/bookmarks",
                """{"bookUrl":"$bookUrl","chapterIndex":0,"chapterTitle":"第1回 雪夜","mode":"paged","position":1,"excerpt":"风雪落了一夜"}""",
            ).getAsJsonObject("data")
            assertTrue(bookmark.get("id").asLong > 0)
            assertEquals(1, bookmark.get("position").asInt)
            val bookmarkList = get(port, "/api/bookmarks?url=${encode(bookUrl)}")
            assertEquals(1, bookmarkList.getAsJsonArray("data").size())
            val deleted = send(
                port,
                "DELETE",
                "/api/bookmarks?id=${bookmark.get("id").asLong}",
                null,
            )
            assertEquals(true, deleted.get("isSuccess").asBoolean)
            assertEquals(0, get(port, "/api/bookmarks?url=${encode(bookUrl)}").getAsJsonArray("data").size())

            val refreshed = post(
                port,
                "/api/books/chapters/refresh",
                """{"url":"$bookUrl"}""",
            )
            assertEquals(2, refreshed.getAsJsonArray("data").size())
            assertEquals(
                1,
                get(port, "/api/books").getAsJsonArray("data")[0]
                    .asJsonObject.get("durChapterIndex").asInt,
            )

            val importedRules = post(
                port,
                "/api/replace-rules/import",
                """
                [
                  {
                    "name": "章节序号",
                    "pattern": "第 (\\d+) 章",
                    "replacement": "第${'$'}1回",
                    "isRegex": true,
                    "scopeTitle": true,
                    "scopeContent": false,
                    "sortOrder": 1
                  },
                  {
                    "name": "去雪",
                    "pattern": "雪落了",
                    "replacement": "风雪落了",
                    "isRegex": false,
                    "scopeContent": true,
                    "sortOrder": 2
                  }
                ]
                """.trimIndent(),
            )
            assertEquals(2, importedRules.getAsJsonObject("data").get("imported").asInt)

            val replacedChapters = get(port, "/api/books/chapters?url=$bookUrl")
            assertEquals(
                "第1回 雪夜",
                replacedChapters.getAsJsonArray("data")[0].asJsonObject.get("title").asString,
            )

            val replacedContent = get(port, "/api/books/content?url=$bookUrl&index=0")
            assertEquals(
                "第一章内容：风雪落了一夜。",
                replacedContent.getAsJsonObject("data").get("content").asString,
            )
            assertEquals(
                "第1回 雪夜",
                replacedContent.getAsJsonObject("data").get("title").asString,
            )
            val blocks = replacedContent.getAsJsonObject("data").getAsJsonArray("blocks")
            val imageBlock = blocks.map { it.asJsonObject }
                .first { it.get("type").asString == "image" }
            assertEquals(fixture.baseUrl() + "/cover.png", imageBlock.get("src").asString)
            assertEquals("插图", imageBlock.get("alt").asString)

            val rules = get(port, "/api/replace-rules").getAsJsonArray("data")
            assertEquals(2, rules.size())

            val disabled = put(port, "/api/settings", """{"replaceEnabled":false}""")
            assertEquals(false, disabled.getAsJsonObject("data").get("replaceEnabled").asBoolean)
            val rawContent = get(port, "/api/books/content?url=$bookUrl&index=0")
            assertEquals(
                "第一章内容：雪落了一夜。",
                rawContent.getAsJsonObject("data").get("content").asString,
            )
            assertEquals(
                "第 1 章 雪夜",
                get(port, "/api/books/chapters?url=$bookUrl")
                    .getAsJsonArray("data")[0].asJsonObject.get("title").asString,
            )

            put(port, "/api/settings", """{"replaceEnabled":true,"theme":"paper"}""")
            val settings = get(port, "/api/settings").getAsJsonObject("data")
            assertEquals("paper", settings.get("theme").asString)
            assertEquals(true, settings.get("replaceEnabled").asBoolean)

            val stats = get(port, "/api/stats").getAsJsonObject("data")
            assertEquals(1, stats.get("bookCount").asInt)
            assertEquals(2, stats.get("replaceRuleCount").asInt)

            val cache = get(port, "/api/cache").getAsJsonObject("data")
            assertTrue(cache.get("chapterRows").asInt >= 1)
            assertTrue(cache.get("coverFiles").asInt >= 1)
            val clearedCover = send(port, "DELETE", "/api/cache?type=cover", null)
            assertEquals(0, clearedCover.getAsJsonObject("data").get("coverFiles").asInt)
            val clearedChapter = send(port, "DELETE", "/api/cache?type=chapter", null)
            assertEquals(0, clearedChapter.getAsJsonObject("data").get("chapterRows").asInt)

            put(port, "/api/settings", """{"cacheEnabled":false}""")
            send(port, "DELETE", "/api/cache?type=chapter", null)
            get(port, "/api/books/content?url=$bookUrl&index=0")
            assertEquals(
                0,
                get(port, "/api/cache").getAsJsonObject("data").get("chapterRows").asInt,
            )
            put(port, "/api/settings", """{"cacheEnabled":true}""")
            send(port, "DELETE", "/api/cache?type=chapter", null)
            get(port, "/api/books/content?url=$bookUrl&index=0")
            assertTrue(
                get(port, "/api/cache").getAsJsonObject("data").get("chapterRows").asInt >= 1,
            )

            // 详情页不可用时，用搜索元数据落库
            val metaUrl = "http://127.0.0.1:1/book/meta"
            val metaAdded = post(
                port,
                "/api/books",
                """
                {
                  "bookUrl": "$metaUrl",
                  "sourceKey": "${fixture.baseUrl()}",
                  "name": "元数据加书",
                  "author": "某作者",
                  "tocUrl": "$metaUrl",
                  "latestChapterTitle": "第 9 章"
                }
                """.trimIndent(),
            )
            val metaBook = metaAdded.getAsJsonObject("data")
            assertEquals("元数据加书", metaBook.get("name").asString)
            assertEquals("某作者", metaBook.get("author").asString)
            assertEquals("第 9 章", metaBook.get("latestChapterTitle").asString)
            assertEquals(0, metaBook.get("totalChapterNum").asInt)
            assertEquals(2, get(port, "/api/books").getAsJsonArray("data").size())
        } finally {
            server.stop()
            fixture.stop()
            dataDir.toFile().deleteRecursively()
        }
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private fun get(port: Int, path: String): com.google.gson.JsonObject =
        send(port, "GET", path, null)

    private fun put(port: Int, path: String, body: String): com.google.gson.JsonObject =
        send(port, "PUT", path, body)

    private fun post(port: Int, path: String, body: String): com.google.gson.JsonObject =
        send(port, "POST", path, body)

    private fun getImage(port: Int, path: String): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Cookie", "sid=test")
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun send(port: Int, method: String, path: String, body: String?): com.google.gson.JsonObject {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Cookie", "sid=test")
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) }
                ?: HttpRequest.BodyPublishers.noBody())
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "$method $path -> ${response.body()}")
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private class FixtureServer {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/search") { exchange -> respond(exchange, SEARCH_HTML) }
            server.createContext("/cover.png") { exchange ->
                val referer = exchange.requestHeaders.getFirst("Referer")
                val userAgent = exchange.requestHeaders.getFirst("User-Agent")
                if (referer == baseUrl() + "/book/1" && userAgent == "FixtureUA") {
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, COVER_BYTES.size.toLong())
                    exchange.responseBody.use { it.write(COVER_BYTES) }
                } else {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                }
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
          "bookSourceName": "中文 Fixture",
          "header": "{\"User-Agent\":\"FixtureUA\",\"Referer\":\"BASE_URL/book/1\"}",
          "coverDecodeJs": "java.base64DecodeToByteArray('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')",
          "searchUrl": "BASE_URL/search?q={{key}}",
          "ruleSearch": {
            "bookList": "class.result@tag.li",
            "name": "tag.a@text",
            "author": "class.author@text",
            "bookUrl": "tag.a@href"
          },
          "ruleBookInfo": {
            "name": "tag.h1@text",
            "author": "class.author@text",
            "intro": "class.intro@text",
            "tocUrl": "class.toc@href",
            "coverUrl": "class.cover@src"
          },
          "ruleToc": {
            "chapterList": "class.chapters@tag.li",
            "chapterName": "tag.a@text",
            "chapterUrl": "tag.a@href"
          },
          "ruleContent": {
            "content": "class.content@html",
            "imageDecode": "java.base64DecodeToByteArray('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')"
          }
        }
        """

        private const val SEARCH_HTML = """
        <html><body><ul class="result">
          <li><a href="/book/1">长夜行</a><span class="author">沈砚</span></li>
          <li><a href="/book/2">星海拾遗</a><span class="author">林渡</span></li>
        </ul></body></html>
        """

        private const val INFO_HTML = """
        <html><body><h1>长夜行</h1><span class="author">沈砚</span>
        <div class="intro">雪夜与旧刀</div><img class="cover" src="/cover.png" />
        <a class="toc" href="/book/1/toc">目录</a></body></html>
        """

        private const val TOC_HTML = """
        <html><body><ul class="chapters">
          <li><a href="/book/1/ch1">第 1 章 雪夜</a></li>
          <li><a href="/book/1/ch2">第 2 章 归人</a></li>
        </ul></body></html>
        """

        private const val CONTENT_HTML = """
        <html><body><div class="content">第一章内容：雪落了一夜。
        <img src="/cover.png" alt="插图" /></div></body></html>
        """

        private val COVER_BYTES: ByteArray = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")

        private val DECODED_COVER_BYTES: ByteArray = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
    }
}
