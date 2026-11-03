package io.legado.desktop.server.api

import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import io.legado.desktop.server.ServerContext

data class SourceMatch(
    val source: BookSource,
    val bookUrl: String,
)

/**
 * 按网址反查书源，对齐 Android `WebBook.getBookInfoByUrlAwait`：
 * 1. URL 选项 `,{"origin":"书源地址"}` 指定；
 * 2. 启用书源的 bookSourceUrl 前缀匹配（取最长）；
 * 3. 启用书源的 bookUrlPattern 正则匹配。
 */
class SourceMatcher(private val context: ServerContext) {

    fun cleanUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        val matcher = AnalyzeUrl.paramPattern.matcher(url)
        if (!matcher.find()) return url
        return url.substring(0, matcher.start()).trim()
    }

    fun match(rawUrl: String, forcedSourceKey: String? = null): SourceMatch? {
        val url = rawUrl.trim()
        if (!url.startsWith("http", true)) return null
        var bookUrl = url
        var originSource: BookSource? = null

        val matcher = AnalyzeUrl.paramPattern.matcher(url)
        if (matcher.find()) {
            val optionText = url.substring(matcher.end())
            val option = GSON.fromJsonObject<AnalyzeUrl.UrlOption>(optionText).getOrNull()
            bookUrl = url.substring(0, matcher.start()).trim()
            originSource = option?.origin?.takeIf { it.isNotBlank() }?.let { origin ->
                context.sourceRepo.get(origin)
            }
        }
        if (!bookUrl.startsWith("http", true)) return null
        // 地址格式校验（Android 用 getBaseUrl 判空）
        NetworkUtils.getBaseUrl(bookUrl) ?: return null

        // 手动指定书源优先（用于镜像域名等自动匹配不到的情况）
        if (!forcedSourceKey.isNullOrBlank()) {
            val forced = context.sourceRepo.get(forcedSourceKey) ?: return null
            return SourceMatch(forced, bookUrl)
        }
        originSource?.let { return SourceMatch(it, bookUrl) }

        val sources = context.sourceRepo.enabled()
        sources
            .filter { it.bookSourceUrl.isNotBlank() && bookUrl.startsWith(it.bookSourceUrl, ignoreCase = true) }
            .maxByOrNull { it.bookSourceUrl.length }
            ?.let { return SourceMatch(it, bookUrl) }

        for (source in sources) {
            val pattern = source.bookUrlPattern?.trim().orEmpty()
            if (pattern.isEmpty()) continue
            val matched = runCatching { Regex(pattern).matches(bookUrl) }.getOrDefault(false)
            if (matched) return SourceMatch(source, bookUrl)
        }
        return null
    }
}
