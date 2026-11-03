package io.legado.desktop.server.http

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.exception.NoStackTraceException
import io.legado.desktop.core.spi.CookieBridge
import io.legado.desktop.core.spi.WebviewTaskResult
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * 连接 Tauri 壳内 WebView2 桥（LEGADO_WEBVIEW_BRIDGE / LEGADO_WEBVIEW_TOKEN）。
 * 只用 java.base 的 HttpURLConnection，避免 jlink 运行时需要额外模块。
 */
class WebCookieBridgeClient : CookieBridge {

    private val bridgePort = System.getenv("LEGADO_WEBVIEW_BRIDGE")?.toIntOrNull()
    private val bridgeToken = System.getenv("LEGADO_WEBVIEW_TOKEN")

    override fun isAvailable(): Boolean = bridgePort != null && !bridgeToken.isNullOrBlank()

    override fun acquireCookies(
        url: String,
        mode: String,
        timeoutMs: Long,
        title: String?,
        userAgent: String?,
    ): Map<String, String> = runTask(
        url = url,
        mode = mode,
        timeoutMs = timeoutMs,
        title = title,
        userAgent = userAgent,
    ).cookies

    override fun runTask(
        url: String,
        mode: String,
        timeoutMs: Long,
        title: String?,
        userAgent: String?,
        javaScript: String?,
        sourceRegex: String?,
        overrideRegex: String?,
        delayMs: Long,
        reportFallbackMs: Long?,
        reuseWindow: Boolean,
        visibleWindow: Boolean,
    ): WebviewTaskResult {
        val body = postTask(
            url, mode, timeoutMs, title, userAgent, javaScript, sourceRegex, overrideRegex,
            delayMs, reportFallbackMs, reuseWindow, visibleWindow,
        )
        val cookies = LinkedHashMap<String, String>()
        body.getAsJsonArray("cookies")?.forEach { element ->
            val item = element.asJsonObject
            val name = item.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val value = item.get("value")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            if (name.isNotBlank() && value.isNotBlank()) cookies[name] = value
        }
        return WebviewTaskResult(
            cookies = cookies,
            value = body.get("value")?.takeIf { !it.isJsonNull }?.asString,
            finalUrl = body.get("finalUrl")?.takeIf { !it.isJsonNull }?.asString,
        )
    }

    private fun postTask(
        url: String,
        mode: String,
        timeoutMs: Long,
        title: String?,
        userAgent: String?,
        javaScript: String?,
        sourceRegex: String?,
        overrideRegex: String?,
        delayMs: Long,
        reportFallbackMs: Long?,
        reuseWindow: Boolean,
        visibleWindow: Boolean,
    ): JsonObject {
        if (!isAvailable()) {
            throw NoStackTraceException("WebView 桥不可用（需要通过桌面壳启动）")
        }
        val port = bridgePort ?: 0
        val token = bridgeToken ?: ""
        val waitMs = timeoutMs.coerceIn(5_000, 600_000)
        val payload = buildString {
            append('{')
            append("\"url\":").append(jsonString(url)).append(',')
            append("\"mode\":").append(jsonString(mode)).append(',')
            append("\"timeout_ms\":").append(waitMs).append(',')
            title?.let { append("\"title\":").append(jsonString(it)).append(',') }
            userAgent?.let { append("\"user_agent\":").append(jsonString(it)).append(',') }
            javaScript?.let { append("\"java_script\":").append(jsonString(it)).append(',') }
            sourceRegex?.let { append("\"source_regex\":").append(jsonString(it)).append(',') }
            overrideRegex?.let { append("\"override_regex\":").append(jsonString(it)).append(',') }
            if (delayMs > 0) append("\"delay_ms\":").append(delayMs).append(',')
            reportFallbackMs?.let { append("\"report_fallback_ms\":").append(it).append(',') }
            if (reuseWindow) append("\"reuse_window\":true,")
            if (visibleWindow) append("\"visible_window\":true,")
            if (endsWith(",")) setLength(length - 1)
            append('}')
        }
        val connection = URI.create("http://127.0.0.1:$port/cookies").toURL()
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = (waitMs + 10_000).toInt()
        connection.setRequestProperty("X-Bridge-Token", token)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setFixedLengthStreamingMode(payload.toByteArray(Charsets.UTF_8).size)
        try {
            connection.outputStream.use { output: OutputStream ->
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw NoStackTraceException("WebView 桥无响应")
            if (code != 200) {
                throw NoStackTraceException("WebView 桥返回 $code：${body.take(200)}")
            }
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("error")?.takeIf { !it.isJsonNull }?.let {
                throw NoStackTraceException("WebView 兜底失败：${it.asString}")
            }
            return obj
        } catch (e: NoStackTraceException) {
            throw e
        } catch (e: java.io.IOException) {
            throw NoStackTraceException("WebView 桥连接失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    override fun setWindowTheme(dark: Boolean) {
        if (!isAvailable()) return
        val port = bridgePort ?: return
        val token = bridgeToken ?: return
        runCatching {
            val payload = if (dark) "{\"theme\":\"dark\"}" else "{\"theme\":\"light\"}"
            val connection = URI.create("http://127.0.0.1:$port/window-theme").toURL()
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.setRequestProperty("X-Bridge-Token", token)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
            connection.disconnect()
        }
    }

    override fun desktopCommand(action: String, payload: String): String? {
        if (!isAvailable()) return null
        val port = bridgePort ?: return null
        val token = bridgeToken ?: return null
        val connection = URI.create("http://127.0.0.1:$port/float/$action").toURL()
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 3_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("X-Bridge-Token", token)
        connection.setRequestProperty("Content-Type", "application/json")
        val bytes = payload.ifBlank { "{}" }.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        try {
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return null
            if (code != 200) {
                throw NoStackTraceException("桌面壳返回 $code：${body.take(200)}")
            }
            return body
        } catch (e: NoStackTraceException) {
            throw e
        } catch (e: java.io.IOException) {
            throw NoStackTraceException("桌面壳连接失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
