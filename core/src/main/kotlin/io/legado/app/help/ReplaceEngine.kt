package io.legado.app.help

import io.legado.app.data.entities.ReplaceRule
import java.util.regex.Pattern

/**
 * 替换净化：按作用域筛选规则，再按 sortOrder 依次应用。
 * 与上游一致：scope/excludeScope 命中书籍名或书源 URL 即生效。
 */
object ReplaceEngine {

    fun applyContent(
        text: String,
        bookName: String,
        bookOrigin: String,
        rules: List<ReplaceRule>,
    ): String = apply(text, effectiveRules(rules, bookName, bookOrigin, forTitle = false))

    fun applyTitle(
        title: String,
        bookName: String,
        bookOrigin: String,
        rules: List<ReplaceRule>,
    ): String = apply(title, effectiveRules(rules, bookName, bookOrigin, forTitle = true))

    fun effectiveRules(
        rules: List<ReplaceRule>,
        bookName: String,
        bookOrigin: String,
        forTitle: Boolean,
    ): List<ReplaceRule> = rules.asSequence()
        .filter { it.isEnabled && it.pattern.isNotBlank() }
        .filter { if (forTitle) it.scopeTitle else it.scopeContent }
        .filter { matches(it.scope, bookName, bookOrigin) }
        .filterNot { excluded(it.excludeScope, bookName, bookOrigin) }
        .sortedBy { it.order }
        .toList()

    private fun matches(scope: String?, bookName: String, bookOrigin: String): Boolean {
        if (scope.isNullOrBlank()) return true
        return containsBook(scope, bookName, bookOrigin)
    }

    private fun excluded(scope: String?, bookName: String, bookOrigin: String): Boolean {
        if (scope.isNullOrBlank()) return false
        return containsBook(scope, bookName, bookOrigin)
    }

    private fun containsBook(scope: String, bookName: String, bookOrigin: String): Boolean =
        (bookName.isNotBlank() && scope.contains(bookName, ignoreCase = true)) ||
            (bookOrigin.isNotBlank() && scope.contains(bookOrigin, ignoreCase = true))

    private fun apply(text: String, rules: List<ReplaceRule>): String {
        var result = text
        for (rule in rules) {
            result = replace(result, rule)
        }
        return result
    }

    fun replace(text: String, rule: ReplaceRule): String {
        if (rule.pattern.isBlank()) return text
        return runCatching {
            if (rule.isRegex) {
                val timeout = if (rule.timeoutMillisecond > 0) rule.timeoutMillisecond else 3000L
                val pattern = Pattern.compile(rule.pattern)
                val matcher = pattern.matcher(TimedCharSequence.create(text, timeout))
                matcher.replaceAll(rule.replacement)
            } else {
                text.replace(rule.pattern, rule.replacement)
            }
        }.getOrDefault(text)
    }

    fun isValidPattern(pattern: String): Boolean =
        pattern.isNotBlank() && runCatching { Pattern.compile(pattern) }.isSuccess

    /**
     * Matcher 每次取字符都会经过这里，正则回溯超时时主动中断，避免服务线程被卡死。
     */
    private class TimedCharSequence private constructor(
        private val text: CharSequence,
        private val deadline: Long,
    ) : CharSequence {

        companion object {
            fun create(text: CharSequence, timeoutMillisecond: Long): TimedCharSequence =
                TimedCharSequence(text, System.nanoTime() + timeoutMillisecond * 1_000_000)
        }

        override val length: Int
            get() = text.length

        override fun get(index: Int): Char {
            if (System.nanoTime() > deadline) throw RegexTimeoutException()
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            TimedCharSequence(text.subSequence(startIndex, endIndex), deadline)

        override fun toString(): String = text.toString()

        private class RegexTimeoutException : RuntimeException("替换规则执行超时")
    }
}
