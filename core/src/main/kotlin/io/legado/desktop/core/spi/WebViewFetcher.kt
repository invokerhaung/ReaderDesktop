package io.legado.desktop.core.spi

data class WebViewRequest(
    val url: String? = null,
    val html: String? = null,
    val javaScript: String? = null,
    val sourceRegex: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val delayMs: Long = 1_000L,
)

data class WebViewResult(
    val url: String,
    val body: String,
    val cookies: Map<String, String> = emptyMap(),
)

/**
 * 原 BackstageWebView 的替代。桌面端由 Tauri/WebView2 实现。
 */
interface WebViewFetcher {
    suspend fun fetch(request: WebViewRequest): WebViewResult
}
