package io.legado.app.utils

import java.net.URL

object NetworkUtils {

    fun getAbsoluteURL(baseURL: String?, relativePath: String): String {
        if (baseURL.isNullOrEmpty()) return relativePath.trim()
        val base = runCatching { URL(baseURL.substringBefore(",")) }.getOrNull()
        return getAbsoluteURL(base, relativePath)
    }

    fun getAbsoluteURL(baseURL: URL?, relativePath: String): String {
        val relative = relativePath.trim()
        if (baseURL == null) return relative
        if (relative.isAbsUrl() || relative.isDataUrl()) return relative
        if (relative.startsWith("javascript", true)) return ""
        return runCatching { URL(baseURL, relative).toString() }.getOrDefault(relative)
    }

    fun getBaseUrl(url: String?): String? {
        url ?: return null
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val index = url.indexOf("/", 9)
        return if (index == -1) url else url.take(index)
    }

    fun getSubDomain(url: String): String {
        val baseUrl = getBaseUrl(url) ?: return url
        return runCatching { URL(baseUrl).host }.getOrDefault(baseUrl)
    }

    fun getDomain(url: String): String {
        val baseUrl = getBaseUrl(url) ?: return url
        return runCatching { URL(baseUrl).host }.getOrDefault(baseUrl)
    }

    fun encodedQuery(str: String): Boolean {
        if (str.isEmpty()) return true
        var i = 0
        while (i < str.length) {
            val c = str[i]
            when {
                c == '%' && i + 2 < str.length -> i += 3
                c == '&' || c == '=' || c == '+' || c == '?' || c == '/' || c == ':' ||
                    c == ';' || c == ',' || c == '@' || c == '#' || c == '[' || c == ']' -> i++
                c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> i++
                else -> return false
            }
        }
        return true
    }

    fun encodedForm(str: String): Boolean = encodedQuery(str)
}
