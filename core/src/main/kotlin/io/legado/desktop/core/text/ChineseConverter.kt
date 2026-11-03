package io.legado.desktop.core.text

import com.github.houbb.opencc4j.util.ZhConverterUtil

/**
 * 简繁转换：基于 opencc4j（OpenCC 词表），只用于显示，不修改章节原始缓存。
 */
object ChineseConverter {
    const val OFF = "off"
    const val SIMPLIFIED = "simplified"
    const val TRADITIONAL = "traditional"

    fun normalizeMode(value: String?): String =
        if (value == SIMPLIFIED || value == TRADITIONAL) value else OFF

    fun convert(text: String, mode: String?): String {
        if (text.isEmpty()) return text
        return when (normalizeMode(mode)) {
            SIMPLIFIED -> runCatching { ZhConverterUtil.toSimple(text) }.getOrDefault(text)
            TRADITIONAL -> runCatching { ZhConverterUtil.toTraditional(text) }.getOrDefault(text)
            else -> text
        }
    }

    fun toSimplified(text: String): String = convert(text, SIMPLIFIED)

    fun toTraditional(text: String): String = convert(text, TRADITIONAL)
}
