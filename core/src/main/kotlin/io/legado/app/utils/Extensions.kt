package io.legado.app.utils

import org.slf4j.LoggerFactory
import java.util.regex.Pattern

fun String?.isAbsUrl(): Boolean =
    this != null && (startsWith("http://", true) || startsWith("https://", true))

fun String?.isDataUrl(): Boolean = this?.startsWith("data:", true) == true

fun String?.isJson(): Boolean {
    val text = this?.trim() ?: return false
    return text.startsWith("{") || text.startsWith("[")
}

fun String?.isXml(): Boolean = this?.trim()?.startsWith("<") == true

inline fun <K, V> MutableMap<K, V>.getOrPutLimit(key: K, maxSize: Int, defaultValue: () -> V): V {
    get(key)?.let { return it }
    if (size >= maxSize) {
        val iterator = entries.iterator()
        if (iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
    val value = defaultValue()
    put(key, value)
    return value
}

fun String.splitNotBlank(vararg delimiters: String, limit: Int = 0): Array<String> {
    if (isBlank()) return emptyArray()
    var result = listOf(this)
    delimiters.filter { it.isNotEmpty() }.forEach { delimiter ->
        result = result.flatMap { part ->
            if (limit > 0 && result.size >= limit) listOf(part)
            else part.split(delimiter)
        }
    }
    return result.map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
}

fun String.splitNotBlank(regex: Regex, limit: Int = 0): Array<String> {
    if (isBlank()) return emptyArray()
    return split(regex, limit).map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
}

fun Throwable.printOnDebug() {
    LoggerFactory.getLogger("legado").debug(stackTraceStr, this)
}

val Throwable.stackTraceStr: String
    get() = stackTraceToString()

fun String.replace(regex: Pattern, replacement: String): String =
    regex.matcher(this).replaceAll(replacement)
