package io.legado.app.help

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.LogUtils
import io.legado.desktop.core.spi.CookieBridgeHolder
import org.jsoup.Jsoup
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

interface JsExtensions : JsEncodeUtils {

    fun getSource(): BaseSource?

    fun ajax(url: Any): String? {
        val urlStr = if (url is List<*>) url.firstOrNull().toString() else url.toString()
        val analyzeUrl = AnalyzeUrl(urlStr, source = getSource())
        return runCatching { analyzeUrl.getStrResponse().body }.getOrElse {
            LogUtils.e("JsExtensions", "ajax($urlStr) failed", it)
            it.stackTraceToString()
        }
    }

    fun connect(urlStr: String): StrResponse =
        AnalyzeUrl(urlStr, source = getSource()).getStrResponse()

    fun connect(urlStr: String, header: String?): StrResponse =
        AnalyzeUrl(urlStr, source = getSource(), headerMapF = parseHeader(header)).getStrResponse()

    /**
     * 用 WebView 加载页面并执行 JS，返回结果（默认 outerHTML）。
     * 对应上游 BackstageWebView 的 html 模式。
     */
    fun webView(html: String?, url: String?, js: String?, delayTime: Long = 1000L): String? =
        runWebviewTask(html, url, js, delayTime, "html")

    /**
     * 用 WebView 嗅探资源地址：命中 [sourceRegex] 或音视频扩展名的请求 URL。
     */
    fun webViewGetSource(
        html: String?,
        url: String?,
        js: String?,
        sourceRegex: String?,
        delayTime: Long = 1000L,
    ): String? = runWebviewTask(html, url, js, delayTime, "source", sourceRegex = sourceRegex)

    /**
     * 用 WebView 等待页面跳转到匹配 [overrideUrlRegex] 的地址。
     */
    fun webViewGetOverrideUrl(
        html: String?,
        url: String?,
        js: String?,
        overrideUrlRegex: String?,
        delayTime: Long = 1000L,
    ): String? = runWebviewTask(
        html, url, js, delayTime, "override", overrideRegex = overrideUrlRegex,
    )

    private fun runWebviewTask(
        html: String?,
        url: String?,
        js: String?,
        delayTime: Long,
        mode: String,
        sourceRegex: String? = null,
        overrideRegex: String? = null,
    ): String? {
        val bridge = CookieBridgeHolder.bridge?.takeIf { it.isAvailable() }
            ?: throw NoStackTraceException("WebView 不可用（需要通过桌面壳启动）")
        val target = url?.takeIf { it.startsWith("http", true) }
            ?: throw NoStackTraceException("webView 需要 http 地址")
        if (!html.isNullOrBlank() && url.isNullOrBlank()) {
            throw NoStackTraceException("webView 暂不支持仅注入 HTML 的模式")
        }
        val result = bridge.runTask(
            url = target,
            mode = mode,
            timeoutMs = 60_000,
            userAgent = getSource()?.getHeaderMap(true)?.get("User-Agent"),
            javaScript = js,
            sourceRegex = sourceRegex,
            overrideRegex = overrideRegex,
            delayMs = delayTime,
        )
        return result.value
    }

    fun getCookie(tag: String): String = CookieStore.getCookie(tag)

    fun getCookie(tag: String, key: String?): String =
        if (key.isNullOrEmpty()) CookieStore.getCookie(tag) else CookieStore.getKey(tag, key)

    fun encodeURI(str: String): String = java.net.URLEncoder.encode(str, "UTF-8")
        .replace("+", "%20")

    fun encodeURI(str: String, enc: String): String =
        java.net.URLEncoder.encode(str, enc).replace("+", "%20")

    fun htmlFormat(str: String): String = Jsoup.parse(str).body().html()

    fun t2s(text: String): String = io.legado.desktop.core.text.ChineseConverter.toSimplified(text)

    fun s2t(text: String): String = io.legado.desktop.core.text.ChineseConverter.toTraditional(text)

    fun getWebViewUA(): String = AppConst.userAgent

    fun timeFormat(time: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(time))

    fun timeFormatUTC(time: Long, format: String, sh: Int): String {
        val sdf = SimpleDateFormat(format, Locale.ROOT)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(Date(time + sh * 3600_000L))
    }

    fun strToBytes(str: String): ByteArray = str.toByteArray(Charsets.UTF_8)

    fun strToBytes(str: String, charset: String): ByteArray = str.toByteArray(charset(charset))

    fun bytesToStr(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    fun bytesToStr(bytes: ByteArray, charset: String): String = String(bytes, charset(charset))

    fun randomUUID(): String = UUID.randomUUID().toString()

    fun toURL(urlStr: String): URL = URL(urlStr)

    fun toURL(url: String, baseUrl: String?): URL =
        if (baseUrl.isNullOrEmpty()) URL(url) else URL(URL(baseUrl), url)

    fun log(msg: Any?): Any? {
        LogUtils.i("JsExtensions", msg?.toString() ?: "null")
        return msg
    }

    fun toast(msg: Any?) = log(msg)

    fun longToast(msg: Any?) = log(msg)

    private fun parseHeader(header: String?): Map<String, String>? {
        if (header.isNullOrBlank()) return null
        return runCatching {
            val element = com.google.gson.JsonParser.parseString(header).asJsonObject
            element.entrySet().associate { it.key to it.value.asString }
        }.getOrNull()
    }
}
