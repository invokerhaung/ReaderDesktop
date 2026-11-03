package io.legado.desktop.core.spi

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 记录只能通过 WebView 访问的主机（Cloudflare 挑战等）。
 * 直连成功过的不记录；一旦 WebView 抓取成功，后续同主机请求直接走 WebView，避免反复触发挑战。
 */
object WebviewHosts {

    private val hosts = ConcurrentHashMap.newKeySet<String>()

    fun hostOf(url: String): String? = runCatching {
        val uri = URI(url)
        uri.host?.lowercase()
    }.getOrNull()

    fun mark(url: String) {
        hostOf(url)?.let { hosts.add(it) }
    }

    fun isMarked(url: String): Boolean = hostOf(url)?.let { hosts.contains(it) } ?: false

    fun clear() {
        hosts.clear()
    }
}
