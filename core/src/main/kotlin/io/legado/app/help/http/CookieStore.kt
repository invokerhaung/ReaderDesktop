package io.legado.app.help.http

import io.legado.app.utils.NetworkUtils
import java.util.concurrent.ConcurrentHashMap

object CookieStore {

    private val store = ConcurrentHashMap<String, String>()

    /** 宿主注册的持久化回调；cookie 为 null 表示该 key 已被删除。 */
    @Volatile
    var changeListener: ((key: String, cookie: String?) -> Unit)? = null

    fun setCookie(url: String, cookie: String?) {
        if (!url.startsWith("http", true)) return
        update(NetworkUtils.getSubDomain(url)) { key ->
            if (cookie.isNullOrBlank()) store.remove(key) else store[key] = cookie
        }
    }

    fun replaceCookie(url: String, cookie: String) {
        if (url.isBlank() || cookie.isBlank()) return
        update(NetworkUtils.getSubDomain(url)) { key ->
            val old = cookieToMap(store[key] ?: "")
            old.putAll(cookieToMap(cookie))
            store[key] = mapToCookie(old) ?: ""
        }
    }

    /** 直接按存储键写入（用于宿主恢复持久化数据）。 */
    fun putRaw(key: String, cookie: String) {
        if (key.isBlank() || cookie.isBlank()) return
        update(key) { store[it] = cookie }
    }

    fun getCookie(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)
        return store[domain] ?: store[url] ?: ""
    }

    fun getKey(url: String, key: String): String {
        val cookie = getCookie(url)
        if (cookie.isBlank()) return ""
        cookie.split(';').forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0 && pair.take(index).trim() == key) {
                return pair.substring(index + 1).trim()
            }
        }
        return ""
    }

    fun removeCookie(url: String) {
        update(NetworkUtils.getSubDomain(url)) { store.remove(it) }
    }

    private fun update(key: String, block: (String) -> Unit) {
        block(key)
        changeListener?.invoke(key, store[key])
    }

    fun cookieToMap(cookie: String): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        if (cookie.isBlank()) return map
        cookie.split(';').forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0) {
                val key = pair.take(index).trim()
                val value = pair.substring(index + 1).trim()
                if (value.isNotEmpty() && value != "null") map[key] = value
            }
        }
        return map
    }

    fun mapToCookie(cookieMap: Map<String, String>?): String? {
        if (cookieMap.isNullOrEmpty()) return null
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
