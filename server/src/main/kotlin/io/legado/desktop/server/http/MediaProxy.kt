package io.legado.desktop.server.http

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.CookieStore
import io.legado.desktop.server.ServerContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class MediaResult(
    val status: Int,
    val contentType: String,
    val stream: InputStream,
    val length: Long,
    val contentRange: String? = null,
    val acceptRanges: String? = null,
)

/**
 * 媒体代理：给图片/视频流补齐书源请求头，支持 Range，并把 m3u8 里的子列表、分片、密钥 URI 改写到本地代理。
 */
class MediaProxy(private val context: ServerContext) {

    private val client = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetch(
        rawUrl: String?,
        sourceKey: String?,
        bookUrl: String?,
        range: String?,
    ): MediaResult? {
        val url = normalizeUrl(rawUrl) ?: return null
        val source = resolveSource(url, sourceKey, bookUrl)
        val headers = buildHeaders(url, source)
        val rangeHeader = range?.takeIf { it.startsWith("bytes=", true) }

        var response = execute(url, headers, rangeHeader) ?: return null
        val contentType = contentTypeOf(url, response)
        if (isM3u8(url, contentType)) {
            response.use {
                val body = it.body?.string() ?: return null
                return bytesResult(rewriteM3u8(body, url, sourceKey, bookUrl))
            }
        }
        if (!response.isSuccessful && response.code != 416) {
            response.close()
            return null
        }
        val body = response.body ?: run {
            response.close()
            return null
        }
        val length = body.contentLength()
        return MediaResult(
            status = response.code,
            contentType = contentType,
            stream = body.byteStream(),
            length = if (length < 0) -1L else length,
            contentRange = response.header("Content-Range"),
            acceptRanges = response.header("Accept-Ranges") ?: "bytes",
        )
    }

    private fun execute(url: String, headers: Map<String, String>, range: String?): Response? = runCatching {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (key, value) ->
            runCatching { builder.header(key, value) }
        }
        if (range != null) builder.header("Range", range)
        client.newCall(builder.build()).execute()
    }.getOrNull()

    private fun buildHeaders(url: String, source: BookSource?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        source?.getHeaderMap(true)?.forEach { (key, value) -> headers[key] = value }
        if (headers.keys.none { it.equals(AppConst.UA_NAME, true) }) {
            headers[AppConst.UA_NAME] = AppConst.userAgent
        }
        if (headers.keys.none { it.equals("Referer", true) }) {
            source?.bookSourceUrl?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        }
        if (headers.keys.none { it.equals("Cookie", true) }) {
            val cookie = CookieStore.getCookie(url)
            if (cookie.isNotBlank()) headers["Cookie"] = cookie
        }
        return headers
    }

    private fun resolveSource(url: String, sourceKey: String?, bookUrl: String?): BookSource? {
        if (!sourceKey.isNullOrBlank()) {
            context.sourceRepo.get(sourceKey)?.let { return it }
        }
        if (!bookUrl.isNullOrBlank()) {
            context.findSource(bookUrl)?.let { return it }
        }
        return context.findSource(url)
    }

    private fun normalizeUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        return uri.toString()
    }

    private fun contentTypeOf(url: String, response: Response): String {
        val fromHeader = response.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (fromHeader != null) return fromHeader
        return when (url.substringAfterLast('?').substringAfterLast('.').lowercase()) {
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/mp2t"
            "m4s" -> "video/iso.segment"
            "mp4", "m4v" -> "video/mp4"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    private fun isM3u8(url: String, contentType: String): Boolean =
        url.substringBefore('?').endsWith(".m3u8", true) ||
            contentType.contains("mpegurl", true) ||
            contentType.contains("x-mpegURL", true)

    private fun bytesResult(text: String): MediaResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return MediaResult(
            status = 200,
            contentType = "application/vnd.apple.mpegurl",
            stream = bytes.inputStream(),
            length = bytes.size.toLong(),
            acceptRanges = "bytes",
        )
    }

    fun rewriteM3u8(body: String, playlistUrl: String, sourceKey: String?, bookUrl: String?): String {
        // 基址必须带尾部斜杠，否则 URI.resolve 会把最后一段当成文件名
        val base = playlistUrl.substringBefore('?').substringBeforeLast('/', "") + "/"
        val output = StringBuilder(body.length + 256)
        body.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> output.append(line).append('\n')
                trimmed.startsWith("#") -> {
                    output.append(rewriteTag(trimmed, base, sourceKey, bookUrl)).append('\n')
                }
                else -> {
                    output.append(proxyUrl(resolveUrl(base, trimmed), sourceKey, bookUrl)).append('\n')
                }
            }
        }
        return output.toString()
    }

    private fun rewriteTag(tag: String, base: String, sourceKey: String?, bookUrl: String?): String {
        if (!tag.contains("URI=\"", true)) return tag
        val sb = StringBuilder(tag.length + 96)
        var index = 0
        while (true) {
            val start = tag.indexOf("URI=\"", index, ignoreCase = true)
            if (start < 0) {
                sb.append(tag, index, tag.length)
                break
            }
            val valueStart = start + 5
            val valueEnd = tag.indexOf('"', valueStart)
            if (valueEnd < 0) {
                sb.append(tag, index, tag.length)
                break
            }
            sb.append(tag, index, start)
            val raw = tag.substring(valueStart, valueEnd)
            val proxied = if (raw.startsWith("data:", true)) raw
            else proxyUrl(resolveUrl(base, raw), sourceKey, bookUrl)
            sb.append("URI=\"").append(proxied).append('"')
            index = valueEnd + 1
        }
        return sb.toString()
    }

    private fun resolveUrl(base: String, value: String): String {
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        if (value.startsWith("//")) return "https:$value"
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }

    private fun proxyUrl(url: String, sourceKey: String?, bookUrl: String?): String {
        val builder = StringBuilder("/media?url=")
        builder.append(URLEncoder.encode(url, Charsets.UTF_8))
        if (!sourceKey.isNullOrBlank()) {
            builder.append("&source=").append(URLEncoder.encode(sourceKey, Charsets.UTF_8))
        }
        if (!bookUrl.isNullOrBlank()) {
            builder.append("&book=").append(URLEncoder.encode(bookUrl, Charsets.UTF_8))
        }
        return builder.toString()
    }
}
