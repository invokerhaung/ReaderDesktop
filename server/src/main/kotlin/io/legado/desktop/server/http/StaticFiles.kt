package io.legado.desktop.server.http

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object StaticFiles {

    private val MIME = mapOf(
        "html" to "text/html; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "wasm" to "application/wasm",
        "pdf" to "application/pdf",
        "epub" to "application/epub+zip",
        "txt" to "text/plain; charset=utf-8",
        "map" to "application/json; charset=utf-8",
    )

    fun serve(root: Path, uri: String): Response {
        val rootAbs = root.toAbsolutePath().normalize()
        val index = rootAbs.resolve("index.html")
        val decoded = runCatching { URLDecoder.decode(uri, StandardCharsets.UTF_8) }.getOrDefault(uri)
        val relative = decoded.removePrefix("/")

        if (relative.contains("..")) return notFound()

        val requested = if (relative.isBlank()) {
            index
        } else {
            runCatching { rootAbs.resolve(relative).normalize() }.getOrElse { return notFound() }
        }

        val target = when {
            requested.startsWith(rootAbs) && Files.isRegularFile(requested) -> requested
            relative.substringAfterLast('/').contains('.') -> return notFound()
            Files.isRegularFile(index) -> index
            else -> return placeholder()
        }

        val mime = MIME[target.fileName.toString().substringAfterLast('.', "").lowercase()]
            ?: "application/octet-stream"
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            mime,
            Files.newInputStream(target),
            Files.size(target),
        )
    }

    private fun notFound(): Response =
        NanoHTTPD.newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain; charset=utf-8",
            "404 Not Found",
        )

    private fun placeholder(): Response = NanoHTTPD.newFixedLengthResponse(
        Response.Status.OK,
        "text/html; charset=utf-8",
        """
        <!doctype html>
        <html lang="zh-CN">
        <head><meta charset="utf-8"><title>Legado Desktop</title></head>
        <body style="font-family:system-ui;padding:40px;line-height:1.7">
          <h1>骨架已启动</h1>
          <p>前端尚未构建。开发模式请在 <code>frontend/</code> 执行 <code>npm install &amp;&amp; npm run dev</code>。</p>
          <p>生产模式请先 <code>npm run build</code>，让 <code>frontend/dist</code> 存在后刷新本页。</p>
          <p>健康检查：<a href="/healthz">/healthz</a></p>
        </body>
        </html>
        """.trimIndent(),
    )
}
