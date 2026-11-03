package io.legado.desktop.server.image

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.CookieStore
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.desktop.server.ServerContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

data class CoverResponse(
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * 封面代理：用书源请求头/Referer 拉取图片，解码书源 coverDecodeJs，并落磁盘缓存。
 */
class CoverProxy(private val context: ServerContext, dataDir: Path) {

    private val logger = LoggerFactory.getLogger(CoverProxy::class.java)
    private val cacheDir = dataDir.resolve("cache").resolve("covers")
    private val cacheTtlMillis = 7L * 24 * 60 * 60 * 1000
    private val maxBytes = 8 * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetch(
        rawUrl: String?,
        sourceKey: String?,
        bookUrl: String?,
        kind: String = "cover",
    ): CoverResponse? {
        val input = rawUrl?.trim().orEmpty()
        if (input.isBlank()) return null
        if (input.startsWith("data:", ignoreCase = true)) return parseDataUri(input)

        val book = bookUrl?.takeIf { it.startsWith("http") }?.let { context.bookRepo.get(it) }
        val source = resolveSource(input, sourceKey, book)
        val url = resolveUrl(input, source, bookUrl) ?: return null
        val key = cacheKey(url, source, bookUrl, kind)

        val cached = readCache(key)
        if (cached != null && cached.fresh) return cached.response

        val fetched = runCatching { download(url, source, book, bookUrl, kind) }
            .onFailure { logger.warn("图片下载失败: $url", it) }
            .getOrNull()
        if (fetched != null) {
            writeCache(key, fetched)
            return fetched
        }
        return cached?.response
    }

    fun stats(): Pair<Long, Int> {
        if (!Files.isDirectory(cacheDir)) return 0L to 0
        return runCatching {
            Files.list(cacheDir).use { stream ->
                val images = stream.filter { it.fileName.toString().endsWith(".img") }.toList()
                images.sumOf { Files.size(it) } to images.size
            }
        }.getOrDefault(0L to 0)
    }

    fun clear() {
        if (!Files.isDirectory(cacheDir)) return
        runCatching {
            Files.walk(cacheDir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }.onFailure {
            logger.warn("封面缓存清理失败: $cacheDir", it)
        }
    }

    private fun resolveSource(url: String, sourceKey: String?, book: Book?): BookSource? {
        if (!sourceKey.isNullOrBlank()) {
            context.sourceRepo.get(sourceKey)?.let { return it }
        }
        if (book != null) {
            context.sourceRepo.get(book.origin)?.let { return it }
        }
        return context.findSource(url)
    }

    private fun resolveUrl(raw: String, source: BookSource?, bookUrl: String?): String? {
        return runCatching {
            val uri = URI(raw)
            val resolved = if (uri.isAbsolute) raw else {
                val base = bookUrl?.takeIf { it.startsWith("http") }
                    ?: source?.bookSourceUrl?.takeIf { it.startsWith("http") }
                    ?: return null
                URL(URL(base), raw).toString()
            }
            val scheme = URI(resolved).scheme?.lowercase()
            if (scheme != "http" && scheme != "https") null else resolved
        }.getOrNull()
    }

    private fun download(
        url: String,
        source: BookSource?,
        book: Book?,
        bookUrl: String?,
        kind: String,
    ): CoverResponse? {
        val headers = source?.getHeaderMap().orEmpty()
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            if (!key.equals("Host", true) && !key.equals("Content-Length", true)) {
                builder.header(key, value)
            }
        }
        if (headers.keys.none { it.equals(AppConst.UA_NAME, true) }) {
            builder.header(AppConst.UA_NAME, AppConst.userAgent)
        }
        if (headers.keys.none { it.equals("Referer", true) }) {
            val referer = bookUrl?.takeIf { it.startsWith("http") }
                ?: source?.bookSourceUrl?.takeIf { it.startsWith("http") }
                ?: originOf(url)
            referer?.let { builder.header("Referer", it) }
        }
        if (headers.keys.none { it.equals("Accept", true) }) {
            builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        }
        if (source != null && source.enabledCookieJar != false &&
            headers.keys.none { it.equals("Cookie", true) }
        ) {
            val cookie = CookieStore.getCookie(source.bookSourceUrl)
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn("图片响应异常 ${response.code}: $url")
                return null
            }
            val body = response.body
            if (body.contentLength() > maxBytes) return null
            val raw = body.byteStream().use { it.readNBytes(maxBytes + 1) }
            if (raw.isEmpty() || raw.size > maxBytes) return null

            val declared = response.header("Content-Type")
            // 部分书源（如荔枝短剧）返回的是加密字节，必须先尝试 coverDecodeJs 再判定格式
            val decoded = decodeImage(source, book, url, raw, kind) ?: raw
            val type = normalizeContentType(declared, decoded)
            if (type == null) {
                logger.warn("图片格式无法识别: $url (${declared ?: "no content-type"})")
                return null
            }
            return CoverResponse(type, decoded)
        }
    }

    private fun decodeImage(
        source: BookSource?,
        book: Book?,
        url: String,
        bytes: ByteArray,
        kind: String,
    ): ByteArray? {
        val js = if (kind == "content") {
            source?.getContentRule()?.imageDecode
        } else {
            source?.coverDecodeJs
        }?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val ruleBook = book ?: Book(bookUrl = url, origin = source?.bookSourceUrl.orEmpty())
            val analyzeRule = AnalyzeRule(ruleBook, source)
            analyzeRule.setContent(url)
            analyzeRule.setCoroutineContext(Dispatchers.IO)
            when (val result = analyzeRule.evalJS(js, bytes)) {
                is ByteArray -> result
                is InputStream -> result.use { it.readBytes() }
                else -> null
            }
        }.onFailure {
            logger.warn("图片解密失败: $url", it)
        }.getOrNull()
    }

    private fun parseDataUri(value: String): CoverResponse? = runCatching {
        val comma = value.indexOf(',')
        if (comma < 0) return null
        val meta = value.substring(5, comma)
        val mime = meta.substringBefore(';').ifBlank { "image/jpeg" }.lowercase()
        if (!mime.startsWith("image/")) return null
        val data = value.substring(comma + 1)
        val bytes = if (meta.contains(";base64", ignoreCase = true)) {
            Base64.getDecoder().decode(data)
        } else {
            data.toByteArray(Charsets.UTF_8)
        }
        if (bytes.isEmpty() || bytes.size > maxBytes) null else CoverResponse(mime, bytes)
    }.getOrNull()

    private fun normalizeContentType(declared: String?, bytes: ByteArray): String? {
        val magic = guessImageType(bytes)
        if (magic != null) return magic
        val mime = declared?.substringBefore(';')?.trim()?.lowercase()
        return mime?.takeIf { it.startsWith("image/") }
    }

    private fun guessImageType(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"

        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"

        bytes.size >= 6 && String(bytes, 0, 6, Charsets.ISO_8859_1)
            .let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"

        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"

        bytes.size >= 12 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> {
            when (String(bytes, 8, 4, Charsets.ISO_8859_1)) {
                "avif", "avis" -> "image/avif"
                "heic", "heix", "hevc", "mif1" -> "image/heic"
                else -> null
            }
        }

        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte() -> "image/bmp"

        bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            bytes[2] == 1.toByte() && bytes[3] == 0.toByte() -> "image/x-icon"

        isSvg(bytes) -> "image/svg+xml"

        else -> null
    }

    private fun isSvg(bytes: ByteArray): Boolean {
        val head = bytes.copyOfRange(0, minOf(bytes.size, 512))
            .toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            .lowercase()
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"))
    }

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme == null || uri.host == null) null
        else "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}/"
    }.getOrNull()

    private fun cacheKey(
        url: String,
        source: BookSource?,
        bookUrl: String?,
        kind: String,
    ): String {
        val raw = buildString {
            append(kind)
            append('|')
            append(url)
            append('|')
            append(source?.bookSourceUrl.orEmpty())
            append('|')
            append(if (kind == "content") source?.getContentRule()?.imageDecode.orEmpty() else source?.coverDecodeJs.orEmpty())
            append('|')
            append(bookUrl.orEmpty())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class Cached(val response: CoverResponse, val fresh: Boolean)

    private fun readCache(key: String): Cached? {
        val image = cacheDir.resolve("$key.img")
        val meta = cacheDir.resolve("$key.type")
        if (!Files.isRegularFile(image)) return null
        return runCatching {
            val type = if (Files.isRegularFile(meta)) {
                Files.readString(meta).trim().ifBlank { "image/jpeg" }
            } else {
                "image/jpeg"
            }
            val age = System.currentTimeMillis() - Files.getLastModifiedTime(image).toMillis()
            Cached(CoverResponse(type, Files.readAllBytes(image)), age in 0..cacheTtlMillis)
        }.getOrNull()
    }

    private fun writeCache(key: String, response: CoverResponse) {
        runCatching {
            Files.createDirectories(cacheDir)
            val image = cacheDir.resolve("$key.img")
            val temp = cacheDir.resolve("$key.tmp")
            Files.write(temp, response.bytes)
            Files.move(temp, image, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(cacheDir.resolve("$key.type"), response.contentType)
        }.onFailure {
            logger.warn("封面缓存写入失败: $key", it)
        }
    }
}
