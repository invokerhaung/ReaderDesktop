package io.legado.app.model.analyzeRule

import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.RequestMethod
import io.legado.app.help.http.StrResponse
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.LogUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.isJson
import io.legado.app.utils.isXml
import io.legado.desktop.core.spi.CookieBridgeHolder
import io.legado.desktop.core.spi.WebviewHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class AnalyzeUrl(
    private val mUrl: String,
    private val key: String? = null,
    private val page: Int? = null,
    private val speakText: String? = null,
    private val speakSpeed: Int? = null,
    private var baseUrl: String = "",
    private val source: BaseSource? = null,
    private val ruleData: RuleDataInterface? = null,
    private val chapter: BookChapter? = null,
    private val readTimeout: Long? = null,
    private val callTimeout: Long? = null,
    private var coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    hasLoginHeader: Boolean = true,
    private val selectedOptions: Map<String, String>? = null,
) : JsExtensions {

    var ruleUrl = ""
        private set
    var rawRuleUrl = ""
        private set
    var url: String = ""
        private set
    val headerMap = LinkedHashMap<String, String>()
    var urlNoQuery: String = ""
        private set
    private var encodedQuery: String? = null
    private var charset: String? = null
    private var method = RequestMethod.GET
    private var body: String? = null
    private var retry = 0
    private var useWebView = false
    private var webJs: String? = null
    private var webViewDelayTime = 1000L
    private var domain: String = ""
    private val enabledCookieJar = source?.enabledCookieJar == true

    init {
        if (baseUrl.isBlank()) baseUrl = source?.getKey() ?: ""
        initUrl()

        val urlHeaders = LinkedHashMap(headerMap)
        if (headerMapF.isNullOrEmpty()) {
            headerMap.putAll(source?.getHeaderMap(hasLoginHeader) ?: emptyMap())
        } else {
            headerMap.putAll(headerMapF)
        }
        if (urlHeaders.isNotEmpty()) headerMap.putAll(urlHeaders)
        if (headerMap.keys.none { it.equals(AppConst.UA_NAME, true) }) {
            headerMap[AppConst.UA_NAME] = AppConst.userAgent
        }
        domain = NetworkUtils.getSubDomain(
            source?.getKey()?.takeIf { it.isAbsUrl() } ?: url
        )
    }

    private fun initUrl() {
        ruleUrl = mUrl
        analyzeJs()
        rawRuleUrl = ruleUrl
        ruleUrl = replaceKeyPage(ruleUrl)
        analyzeUrl()
    }

    private fun analyzeJs() {
        if (!ruleUrl.contains("js", true)) return
        var start = 0
        val matcher = AppPattern.JS_PATTERN.matcher(ruleUrl)
        var result = ruleUrl
        while (matcher.find()) {
            val prefix = ruleUrl.substring(start, matcher.start()).trim()
            if (prefix.isNotEmpty()) result = prefix.replace("@result", result)
            val js = matcher.group(2) ?: matcher.group(1)
            result = (evalJS(js, result) ?: "").toString()
            start = matcher.end()
        }
        if (start > 0) {
            val suffix = ruleUrl.substring(start).trim()
            if (suffix.isNotEmpty()) result = result + suffix
            ruleUrl = result
        }
    }

    private fun replaceKeyPage(curRuleUrl: String): String {
        var result = curRuleUrl
        if (result.contains("{{")) {
            result = result.replace("{{key}}", encode(key ?: ""))
            result = result.replace("{{page}}", page?.toString() ?: "1")
            if (result.contains("{{")) {
                val regex = Regex("\\{\\{([\\w\\W]*?)\\}\\}")
                result = regex.replace(result) { match ->
                    (evalJS(match.groupValues[1]) ?: "").toString()
                }
            }
        }
        return result
    }

    private fun analyzeUrl() {
        var urlNoOption = ruleUrl
        var optionStr: String? = null
        val matcher = paramPattern.matcher(ruleUrl)
        if (matcher.find()) {
            urlNoOption = ruleUrl.substring(0, matcher.start())
            optionStr = ruleUrl.substring(matcher.end())
        }
        url = if (urlNoOption.startsWith("data:")) urlNoOption
        else NetworkUtils.getAbsoluteURL(baseUrl, urlNoOption)
        NetworkUtils.getBaseUrl(url)?.let { baseUrl = it }

        optionStr?.let { json ->
            val option = GSONStrict.fromJsonObject<UrlOption>(json).getOrNull()
                ?: GSON.fromJsonObject<UrlOption>(json).getOrNull()
            option?.let {
                if (it.getMethod()?.equals("POST", true) == true) method = RequestMethod.POST
                it.getHeaderMap()?.forEach { (k, v) -> headerMap[k] = v }
                body = it.getBody()
                charset = it.charset
                retry = it.retry ?: 2
                useWebView = it.useWebView()
                webJs = it.webJs
                webViewDelayTime = (it.webViewDelayTime ?: 1000L).coerceIn(0L, 30_000L)
                it.js?.let { js -> (evalJS(js, url) ?: "").toString().let { newUrl -> if (newUrl.isNotBlank()) url = newUrl } }
            }
        }

        urlNoQuery = url
        if (method == RequestMethod.GET) {
            val pos = url.indexOf('?')
            if (pos != -1) {
                encodedQuery = encodeParams(url.substring(pos + 1), charset)
                urlNoQuery = url.substring(0, pos)
            }
        } else {
            val contentType = headerMap.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            if (body != null && !body!!.isJson() && !body!!.isXml() && contentType.isNullOrEmpty()) {
                body = encodeParams(body!!, charset)
                headerMap["Content-Type"] = "application/x-www-form-urlencoded"
            }
        }
    }

    private fun encodeParams(params: String, charsetName: String?): String {
        if (NetworkUtils.encodedQuery(params)) return params
        val cs = runCatching { Charset.forName(charsetName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        return params.split("&").joinToString("&") { pair ->
            val index = pair.indexOf('=')
            if (index == -1) encode(pair, cs)
            else encode(pair.substring(0, index), cs) + "=" + encode(pair.substring(index + 1), cs)
        }
    }

    private fun encode(value: String, charset: Charset = Charsets.UTF_8): String =
        URLEncoder.encode(value, charset).replace("+", "%20")

    fun evalJS(jsStr: String, result: Any? = null): Any? {
        val bindings = buildScriptBindings { b ->
            b["java"] = this
            b["baseUrl"] = url
            b["cookie"] = CookieStore
            b["cache"] = CacheManager
            b["page"] = page
            b["key"] = key
            b["speakText"] = speakText
            b["speakSpeed"] = speakSpeed
            b["book"] = ruleData as? Book
            b["source"] = source
            b["result"] = result
            b.dangerousApi = source?.enableDangerousApi == true
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        return RhinoScriptEngine.compile(jsStr).eval(scope, coroutineContext)
    }

    fun put(key: String, value: String): String {
        chapter?.putVariable(key, value) ?: ruleData?.putVariable(key, value)
        return value
    }

    fun get(key: String): String = when (key) {
        "bookName" -> (ruleData as? Book)?.name ?: ""
        "title" -> chapter?.title ?: ""
        else -> chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    suspend fun getStrResponseAwait(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
    ): StrResponse {
        getByteArrayIfDataUri()?.let {
            return StrResponse(url, decode(it, null))
        }
        if (this.useWebView && useWebView) {
            fetchWithWebView(jsStr, sourceRegex)?.let { html ->
                return StrResponse(url, html)
            }
            if (!acquireWebViewCookies("challenge", url)) {
                throw UnsupportedOperationException("该书源需要 WebView 抓取，但当前环境未提供 WebView 支持")
            }
        }
        // 已知需要 CF 挑战的主机：直接用 WebView 渲染，避免每次直连都 403
        var challengeTried = false
        if (method == RequestMethod.GET && WebviewHosts.isMarked(url)) {
            challengeTried = true
            fetchWithWebViewChallenge()?.let { html ->
                return StrResponse(url, html)
            }
        }
        return withContext(Dispatchers.IO) {
            setCookie()
            var lastError: Exception? = null
            var webViewFallbackTried = false
            var attemptsLeft = retry + 1
            while (attemptsLeft > 0) {
                attemptsLeft--
                try {
                    val request = buildRequest()
                    okHttpClient.newCall(request).execute().use { response ->
                        val code = response.code
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        saveCookie(response.headers.values("Set-Cookie"))
                        if ((code == 403 || code == 412 || code == 503) && !webViewFallbackTried) {
                            webViewFallbackTried = true
                            if (method == RequestMethod.GET && !challengeTried) {
                                challengeTried = true
                                fetchWithWebViewChallenge()?.let { html ->
                                    return@withContext StrResponse(url, html)
                                }
                            }
                        }
                        return@withContext StrResponse(
                            response,
                            decode(bytes, response.header("Content-Type")),
                        )
                    }
                } catch (e: Exception) {
                    lastError = e
                    // TLS/连接被站点拒绝（指纹风控）时，同样回退到 WebView 渲染
                    if (method == RequestMethod.GET && !challengeTried) {
                        challengeTried = true
                        fetchWithWebViewChallenge()?.let { html ->
                            return@withContext StrResponse(url, html)
                        }
                    }
                    if (attemptsLeft > 0) Thread.sleep(400)
                }
            }
            throw lastError ?: IllegalStateException("请求失败")
        }
    }

    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
    ): StrResponse = runBlocking(coroutineContext) {
        getStrResponseAwait(jsStr, sourceRegex, useWebView)
    }

    private fun buildRequest(): Request {
        val fullUrl = if (encodedQuery.isNullOrEmpty()) urlNoQuery
        else "$urlNoQuery?$encodedQuery"
        val builder = Request.Builder().url(fullUrl)
        headerMap.forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            RequestMethod.POST -> {
                val contentType = headerMap.entries
                    .firstOrNull { it.key.equals("Content-Type", true) }?.value
                    ?: "application/x-www-form-urlencoded"
                builder.post((body ?: "").toRequestBody(contentType.toMediaType()))
            }

            else -> builder.get()
        }
        return builder.build()
    }

    private fun setCookie() {
        if (!enabledCookieJar) return
        val cookie = CookieStore.getCookie(domain)
        if (cookie.isNotEmpty()) {
            val old = headerMap["Cookie"]
            headerMap["Cookie"] = if (old.isNullOrEmpty()) cookie else "$old; $cookie"
        }
    }

    private fun saveCookie(setCookies: List<String>) {
        if (!enabledCookieJar) return
        setCookies.forEach { raw ->
            val pair = raw.substringBefore(';').trim()
            if (pair.contains('=')) CookieStore.replaceCookie(url, pair)
        }
    }

    private fun acquireWebViewCookies(mode: String, targetUrl: String): Boolean {
        val bridge = CookieBridgeHolder.bridge ?: return false
        if (!bridge.isAvailable()) return false
        return try {
            val cookies = bridge.acquireCookies(
                url = targetUrl,
                mode = mode,
                timeoutMs = if (mode == "login") 300_000 else 120_000,
                title = source?.getTag(),
                userAgent = getUserAgent(),
            )
            if (cookies.isEmpty()) return false
            val cookieText = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            CookieStore.replaceCookie(targetUrl, cookieText)
            val old = headerMap["Cookie"]
            headerMap["Cookie"] = if (old.isNullOrEmpty()) cookieText else "$old; $cookieText"
            true
        } catch (e: Exception) {
            LogUtils.e("WebViewBridge", "获取 Cookie 失败: $targetUrl", e)
            false
        }
    }

    /**
     * Cloudflare 挑战：用隐藏 WebView 打开页面，等挑战完成后取渲染后的 HTML。
     * 成功一次后记住主机，后续请求直接走 WebView。
     */
    private data class ChallengeAttempt(
        val delayMs: Long,
        val visible: Boolean,
        val fallbackMs: Long,
        val timeoutMs: Long,
    )

    private fun fetchWithWebViewChallenge(): String? {
        val bridge = CookieBridgeHolder.bridge?.takeIf { it.isAvailable() } ?: return null
        // 先用隐藏窗口自动尝试；最后一步打开可见窗口，必要时由用户点一次验证
        val attempts = listOf(
            // 先给隐藏窗口两次自动过挑战的机会；失败后必须把复用窗口显示出来让用户点验证
            ChallengeAttempt(4_000L, visible = false, fallbackMs = 20_000L, timeoutMs = 30_000L),
            ChallengeAttempt(8_000L, visible = false, fallbackMs = 20_000L, timeoutMs = 30_000L),
            ChallengeAttempt(0L, visible = true, fallbackMs = 180_000L, timeoutMs = 200_000L),
        )
        for (attempt in attempts) {
            val started = System.currentTimeMillis()
            val result = runCatching {
                bridge.runTask(
                    url = url,
                    mode = "html",
                    timeoutMs = attempt.timeoutMs,
                    title = if (attempt.visible) "完成安全验证" else source?.getTag(),
                    // 不伪装书源 UA：桌面 WebView2 用原生指纹，CF 才容易通过
                    userAgent = null,
                    delayMs = attempt.delayMs,
                    reportFallbackMs = attempt.fallbackMs,
                    reuseWindow = true,
                    visibleWindow = attempt.visible,
                )
            }.getOrNull() ?: continue
            val elapsed = System.currentTimeMillis() - started
            val body = result.value?.trim().orEmpty()
            if (result.cookies.isNotEmpty()) {
                CookieStore.replaceCookie(
                    url,
                    result.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
                )
            }
            val challenge = body.isEmpty() || isChallengeHtml(body)
            LogUtils.e(
                "WebViewBridge",
                "CF 抓取 ${if (challenge) "仍被拦截" else "成功"} visible=${attempt.visible} url=$url delay=${attempt.delayMs} elapsed=${elapsed}ms size=${body.length}",
            )
            if (!challenge) {
                WebviewHosts.mark(url)
                return body
            }
        }
        return null
    }

    private fun isChallengeHtml(body: String): Boolean {
        val sample = body.take(4000).lowercase()
        return sample.contains("just a moment") ||
            sample.contains("cf-chl") ||
            sample.contains("__cf_chl") ||
            sample.contains("attention required") ||
            sample.contains("cf-browser-verification")
    }

    /**
     * @webView 选项：优先用 WebView 渲染页面（html 模式）或嗅探资源（source 模式）。
     */
    private fun fetchWithWebView(jsStr: String?, sourceRegex: String?): String? {
        val bridge = CookieBridgeHolder.bridge?.takeIf { it.isAvailable() } ?: return null
        val mode = if (!sourceRegex.isNullOrBlank()) "source" else "html"
        return try {
            val result = bridge.runTask(
                url = url,
                mode = mode,
                timeoutMs = 60_000,
                title = source?.getTag(),
                userAgent = getUserAgent(),
                javaScript = webJs ?: jsStr,
                sourceRegex = sourceRegex,
                delayMs = webViewDelayTime,
            )
            if (result.cookies.isNotEmpty()) {
                CookieStore.replaceCookie(
                    url,
                    result.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
                )
            }
            result.value
        } catch (e: Exception) {
            LogUtils.e("WebViewBridge", "WebView 抓取失败: $url", e)
            null
        }
    }

    private fun getByteArrayIfDataUri(): ByteArray? {
        if (!url.startsWith("data:")) return null
        val pos = urlNoQuery.indexOf(";base64,")
        return if (pos != -1) {
            java.util.Base64.getDecoder().decode(urlNoQuery.substring(pos + 8))
        } else ByteArray(0)
    }

    private fun decode(bytes: ByteArray, contentType: String?): String {
        val cs = charset
            ?: contentType?.substringAfter("charset=", "")?.trim()?.substringBefore(';')
                ?.takeIf { it.isNotBlank() }
        return if (cs.isNullOrBlank()) String(bytes, Charsets.UTF_8)
        else runCatching { String(bytes, Charset.forName(cs)) }
            .getOrDefault(String(bytes, Charsets.UTF_8))
    }

    fun getUserAgent(): String =
        headerMap.entries.firstOrNull { it.key.equals(AppConst.UA_NAME, true) }?.value
            ?: AppConst.userAgent

    fun isPost(): Boolean = method == RequestMethod.POST

    override fun getSource(): BaseSource? = source

    data class UrlOption(
        @com.google.gson.annotations.SerializedName("method")
        var httpMethod: String? = null,
        var charset: String? = null,
        var headers: Any? = null,
        @com.google.gson.annotations.SerializedName("body")
        var bodyData: Any? = null,
        var retry: Int? = null,
        var type: String? = null,
        var webView: Any? = null,
        var webJs: String? = null,
        var webViewDelayTime: Long? = null,
        var origin: String? = null,
        var js: String? = null,
    ) {
        fun getMethod(): String? = httpMethod

        fun getHeaderMap(): Map<String, String>? = when (val value = headers) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value.toString() }
            is String -> GSON.fromJsonObject<Map<String, String>>(value).getOrNull()
            else -> null
        }

        fun getBody(): String? = bodyData?.toString()

        fun useWebView(): Boolean = when (val value = webView) {
            is Boolean -> value
            is String -> value.equals("true", true)
            else -> false
        }
    }

    companion object {
        val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")

        private val trustAll by lazy {
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        }

        private val okHttpClient: OkHttpClient by lazy {
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustAll), java.security.SecureRandom())
            }
            OkHttpClient.Builder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }
}
