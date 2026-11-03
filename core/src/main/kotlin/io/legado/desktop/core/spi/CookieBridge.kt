package io.legado.desktop.core.spi

data class WebviewTaskResult(
    val cookies: Map<String, String> = emptyMap(),
    val value: String? = null,
    val finalUrl: String? = null,
)

/**
 * 由桌面壳（Tauri/WebView2）提供的 Cookie 获取能力：
 * 打开可见 WebView 窗口完成 Cloudflare 挑战或用户登录，再回收 Cookie。
 * 也支持在 WebView 中执行 JS / 嗅探资源地址（对应上游 BackstageWebView）。
 */
interface CookieBridge {
    fun isAvailable(): Boolean

    /**
     * @param mode challenge：页面加载完成后自动收集并关闭窗口；login：等待用户完成登录后手动关闭。
     * @return name -> value 形式的 Cookie 集合
     */
    fun acquireCookies(
        url: String,
        mode: String = "challenge",
        timeoutMs: Long = 120_000,
        title: String? = null,
        userAgent: String? = null,
    ): Map<String, String>

    /**
     * 通用 WebView 任务：
     * - html：加载页面后执行 [javaScript]（默认取 outerHTML），返回结果文本
     * - source：监听 fetch/XHR/资源时序，命中 [sourceRegex]（或音视频扩展名）后返回 URL
     * - override：页面跳转到匹配 [overrideRegex] 的地址时返回该地址
     */
    fun runTask(
        url: String,
        mode: String,
        timeoutMs: Long = 120_000,
        title: String? = null,
        userAgent: String? = null,
        javaScript: String? = null,
        sourceRegex: String? = null,
        overrideRegex: String? = null,
        delayMs: Long = 0,
        reportFallbackMs: Long? = null,
        reuseWindow: Boolean = false,
        visibleWindow: Boolean = false,
    ): WebviewTaskResult =
        WebviewTaskResult(acquireCookies(url, mode, timeoutMs, title, userAgent))

    /** 让桌面壳的原生窗口标题栏跟随深浅色主题。 */
    fun setWindowTheme(dark: Boolean) {}

    /**
     * 调用桌面壳的窗口能力（浮窗开关、置顶、拖动、缩放、全局热键等）。
     * @param action 例如 open / toggle / hide / top / hotkey / drag / resize / state
     * @return 桌面壳返回的 JSON 文本；桥不可用时返回 null。
     */
    fun desktopCommand(action: String, payload: String): String? = null
}

object CookieBridgeHolder {
    @Volatile
    var bridge: CookieBridge? = null
}
