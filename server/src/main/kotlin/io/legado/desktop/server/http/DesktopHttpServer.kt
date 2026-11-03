package io.legado.desktop.server.http

import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import io.legado.desktop.core.Engine
import io.legado.desktop.server.ServerOptions
import io.legado.desktop.server.api.ApiRouter
import io.legado.desktop.server.image.CoverProxy
import fi.iki.elonen.NanoHTTPD.Method
import java.io.ByteArrayInputStream

class DesktopHttpServer(
    private val options: ServerOptions,
    private val engine: Engine,
    private val apiRouter: ApiRouter,
    private val coverProxy: CoverProxy,
    private val mediaProxy: MediaProxy,
) : NanoHTTPD(options.host, options.port) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri
            when {
                uri == "/healthz" -> json(Response.Status.OK, mapOf("status" to "ok", "version" to engine.version))
                uri == "/bootstrap" -> bootstrap(session)
                uri == "/img" -> if (authorized(session)) {
                    cover(session)
                } else {
                    json(Response.Status.UNAUTHORIZED, mapOf("error" to "unauthorized"))
                }
                uri == "/media" -> if (authorized(session)) {
                    media(session)
                } else {
                    json(Response.Status.UNAUTHORIZED, mapOf("error" to "unauthorized"))
                }
                uri.startsWith("/api/") -> if (authorized(session)) {
                    if (uri == "/api/search/stream" && session.method == Method.GET) {
                        searchStream(session)
                    } else {
                        api(session)
                    }
                } else {
                    json(Response.Status.UNAUTHORIZED, mapOf("error" to "unauthorized"))
                }
                else -> StaticFiles.serve(options.staticDir, uri)
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    /**
     * 用一次性 token 换取 HttpOnly Cookie，后续前端请求同源自带凭证。
     */
    private fun bootstrap(session: IHTTPSession): Response {
        val token = session.parameters["token"]?.firstOrNull()
        if (token != options.token) {
            return json(Response.Status.FORBIDDEN, mapOf("error" to "invalid token"))
        }
        val redirect = session.parameters["redirect"]?.firstOrNull()
            ?.takeIf { it.startsWith("/") && !it.startsWith("//") && '\r' !in it && '\n' !in it }
            ?: "/"
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.REDIRECT_SEE_OTHER,
            "text/plain; charset=utf-8",
            "",
        )
        response.addHeader("Location", redirect)
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Set-Cookie", "sid=${options.token}; Path=/; HttpOnly; SameSite=Strict")
        return response
    }

    private fun authorized(session: IHTTPSession): Boolean {
        if (options.devNoAuth) return true
        val raw = session.headers.entries
            .firstOrNull { it.key.equals("cookie", ignoreCase = true) }
            ?.value
            ?: return false
        return raw.split(';').any { it.trim() == "sid=${options.token}" }
    }

    private fun api(session: IHTTPSession): Response = when (session.uri) {        "/api/ping" -> json(Response.Status.OK, mapOf("pong" to engine.ping()))
        "/api/about" -> json(Response.Status.OK, engine.describe())
        else -> {
            val body = readBody(session)
            val payload = apiRouter.handle(
                method = session.method.name,
                uri = session.uri,
                params = session.parameters,
                body = body,
            )
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.lookup(payload.status) ?: Response.Status.OK,
                "application/json; charset=utf-8",
                payload.json,
            )
        }
    }

    /**
     * SSE：GET /api/search/stream?kw=&source=
     */
    private fun searchStream(session: IHTTPSession): Response {
        val keyword = session.parameters["kw"]?.firstOrNull()?.trim()
        if (keyword.isNullOrEmpty()) {
            return json(Response.Status.BAD_REQUEST, mapOf("error" to "缺少 kw"))
        }
        val sourceKey = session.parameters["source"]?.firstOrNull()
        val sink = java.io.PipedOutputStream()
        val input = java.io.PipedInputStream(sink, 256 * 1024)
        val response = NanoHTTPD.newChunkedResponse(
            Response.Status.OK,
            "text/event-stream; charset=utf-8",
            input,
        ).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
        }
        Thread {
            try {
                sink.write(": connected\n\n".toByteArray())
                sink.flush()
                apiRouter.searchStream(keyword, sourceKey) { event, data ->
                    sink.write("event: $event\ndata: ${gson.toJson(data)}\n\n".toByteArray())
                    sink.flush()
                }
            } catch (_: java.io.IOException) {
                // 客户端断开
            } catch (e: Exception) {
                runCatching {
                    sink.write(
                        "event: done\ndata: ${gson.toJson(mapOf("error" to (e.message ?: "unknown")))}\n\n".toByteArray()
                    )
                    sink.flush()
                }
            } finally {
                runCatching { sink.close() }
            }
        }.apply { isDaemon = true }.start()
        return response
    }

    private fun media(session: IHTTPSession): Response {
        val range = session.headers.entries
            .firstOrNull { it.key.equals("range", true) }
            ?.value
        val result = runCatching {
            mediaProxy.fetch(
                rawUrl = session.parameters["url"]?.firstOrNull(),
                sourceKey = session.parameters["source"]?.firstOrNull(),
                bookUrl = session.parameters["book"]?.firstOrNull(),
                range = range,
            )
        }.getOrNull() ?: return NanoHTTPD.newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain; charset=utf-8",
            "",
        )
        val status = if (result.status == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        return NanoHTTPD.newFixedLengthResponse(
            status,
            result.contentType,
            result.stream,
            result.length,
        ).apply {
            addHeader("Accept-Ranges", result.acceptRanges ?: "bytes")
            result.contentRange?.let { addHeader("Content-Range", it) }
            addHeader(
                "Cache-Control",
                if (result.contentType.contains("mpegurl", true)) "no-store" else "public, max-age=3600",
            )
            addHeader("X-Content-Type-Options", "nosniff")
        }
    }

    private fun cover(session: IHTTPSession): Response {        val image = runCatching {
            coverProxy.fetch(
                rawUrl = session.parameters["url"]?.firstOrNull(),
                sourceKey = session.parameters["source"]?.firstOrNull(),
                bookUrl = session.parameters["book"]?.firstOrNull(),
                kind = session.parameters["kind"]?.firstOrNull() ?: "cover",
            )
        }.getOrNull() ?: return NanoHTTPD.newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain; charset=utf-8",
            "",
        )
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            image.contentType,
            ByteArrayInputStream(image.bytes),
            image.bytes.size.toLong(),
        ).apply {
            addHeader("Cache-Control", "public, max-age=86400")
            addHeader("X-Content-Type-Options", "nosniff")
        }
    }

    private fun readBody(session: IHTTPSession): String {        if (session.method == Method.GET || session.method == Method.HEAD) return ""
        val contentType = session.headers.entries
            .firstOrNull { it.key.equals("content-type", true) }
            ?.value
            .orEmpty()
        // NanoHTTPD 2.3.1 的 parseBody 会按 Content-Type 的 charset 解码，JSON 默认会变成 ISO-8859-1，
        // 中文书源名/规则会乱码；JSON 与 PUT 直接按 UTF-8 读原始 body。
        if (session.method == Method.PUT || contentType.contains("application/json", true)) {
            val length = session.headers.entries
                .firstOrNull { it.key.equals("content-length", true) }
                ?.value
                ?.toLongOrNull()
            if (length != null && length > 0) {
                return String(
                    session.inputStream.readNBytes(length.toInt()),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            }
        }
        val files = HashMap<String, String>()
        return runCatching {
            session.parseBody(files)
            files["postData"] ?: ""
        }.getOrDefault("")
    }

    private fun json(status: Response.Status, body: Any): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", gson.toJson(body))
}
