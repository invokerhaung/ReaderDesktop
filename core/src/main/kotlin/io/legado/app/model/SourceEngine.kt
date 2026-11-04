package io.legado.app.model

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.getBookType
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.currentCoroutineContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Phase 0 引擎：搜索、详情、目录、正文。
 * 规则语义与上游 webBook 对齐，暂不包含缓存、发现、并发翻页。
 */
class SourceEngine(private val source: BookSource) {

    suspend fun search(keyword: String, page: Int = 1): List<SearchBook> {
        val searchUrl = source.searchUrl
            ?: throw NoStackTraceException("书源未配置 searchUrl")
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            mUrl = searchUrl,
            key = keyword,
            page = page,
            source = source,
            ruleData = ruleData,
            coroutineContext = currentCoroutineContext(),
        )
        val response = analyzeUrl.getStrResponseAwait()
        val body = response.body ?: throw NoStackTraceException("搜索页内容为空")
        val rule = source.getSearchRule()
        return parseBookList(
            body = body,
            baseUrl = response.url,
            ruleData = ruleData,
            listRule = ListRule(
                bookList = rule.bookList,
                name = rule.name,
                author = rule.author,
                bookUrl = rule.bookUrl,
                coverUrl = rule.coverUrl,
                intro = rule.intro,
                kind = rule.kind,
                lastChapter = rule.lastChapter,
                wordCount = rule.wordCount,
            ),
            emptyMessage = "书源未配置搜索列表规则",
        )
    }

    suspend fun explore(url: String, page: Int = 1): List<SearchBook> {
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            mUrl = url,
            page = page,
            source = source,
            ruleData = ruleData,
            coroutineContext = currentCoroutineContext(),
        )
        val response = analyzeUrl.getStrResponseAwait()
        val body = response.body ?: throw NoStackTraceException("发现页内容为空")
        val rule = source.getExploreRule()
        return parseBookList(
            body = body,
            baseUrl = response.url,
            ruleData = ruleData,
            listRule = ListRule(
                bookList = rule.bookList,
                name = rule.name,
                author = rule.author,
                bookUrl = rule.bookUrl,
                coverUrl = rule.coverUrl,
                intro = rule.intro,
                kind = rule.kind,
                lastChapter = rule.lastChapter,
                wordCount = rule.wordCount,
            ),
            emptyMessage = "书源未配置发现列表规则",
        )
    }

    private data class ListRule(
        val bookList: String?,
        val name: String?,
        val author: String?,
        val bookUrl: String?,
        val coverUrl: String?,
        val intro: String?,
        val kind: String?,
        val lastChapter: String?,
        val wordCount: String?,
    )

    private suspend fun parseBookList(
        body: String,
        baseUrl: String,
        ruleData: RuleData,
        listRule: ListRule,
        emptyMessage: String,
    ): List<SearchBook> {
        val analyzeRule = AnalyzeRule(ruleData, source)
        analyzeRule.setContent(body)
        analyzeRule.setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(baseUrl)
        analyzeRule.setCoroutineContext(currentCoroutineContext())

        val (parsedListRule, reverse) = parseRulePrefix(listRule.bookList)
            ?: throw NoStackTraceException(emptyMessage)
        val items = analyzeRule.getElements(parsedListRule)
        val result = ArrayList<SearchBook>(items.size)
        for (item in items) {
            val name = analyzeRule.getString(listRule.name, item).trim()
            if (name.isEmpty()) continue
            val author = analyzeRule.getString(listRule.author, item).trim()
            val bookUrl = analyzeRule.getString(listRule.bookUrl, item, isUrl = true)
                .ifBlank { baseUrl }
            result.add(
                SearchBook(
                    name = name,
                    author = author,
                    bookUrl = bookUrl,
                    origin = source.bookSourceUrl,
                    originName = source.bookSourceName,
                    tocUrl = bookUrl,
                    coverUrl = analyzeRule.getString(listRule.coverUrl, item, isUrl = true)
                        .takeIf { it.isNotBlank() },
                    intro = analyzeRule.getString(listRule.intro, item).takeIf { it.isNotBlank() },
                    kind = analyzeRule.getString(listRule.kind, item).takeIf { it.isNotBlank() },
                    lastChapter = analyzeRule.getString(listRule.lastChapter, item)
                        .takeIf { it.isNotBlank() },
                    wordCount = analyzeRule.getString(listRule.wordCount, item)
                        .takeIf { it.isNotBlank() },
                    infoHtml = body,
                    type = BookType.fromSourceType(source.getBookType()),
                )
            )
        }
        if (reverse) result.reverse()
        return result
    }

    suspend fun bookInfo(bookUrl: String): Book {
        val book = Book(
            bookUrl = bookUrl,
            origin = source.bookSourceUrl,
            originName = source.bookSourceName,
            tocUrl = bookUrl,
            type = BookType.fromSourceType(source.getBookType()),
        )
        val analyzeUrl = AnalyzeUrl(
            mUrl = bookUrl,
            source = source,
            ruleData = book,
            coroutineContext = currentCoroutineContext(),
        )
        val response = analyzeUrl.getStrResponseAwait()
        val body = response.body ?: throw NoStackTraceException("详情页内容为空")
        val baseUrl = response.url
        val rule = source.getBookInfoRule()
        val analyzeRule = AnalyzeRule(book, source)
        analyzeRule.setContent(body)
        analyzeRule.setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(baseUrl)
        analyzeRule.setCoroutineContext(currentCoroutineContext())

        book.infoHtml = body
        analyzeRule.getString(rule.name).takeIf { it.isNotBlank() }?.let { book.name = it }
        analyzeRule.getString(rule.author).takeIf { it.isNotBlank() }?.let { book.author = it }
        analyzeRule.getString(rule.intro).takeIf { it.isNotBlank() }?.let { book.intro = it }
        analyzeRule.getString(rule.kind).takeIf { it.isNotBlank() }?.let { book.kind = it }
        analyzeRule.getString(rule.wordCount).takeIf { it.isNotBlank() }?.let { book.wordCount = it }
        analyzeRule.getString(rule.lastChapter).takeIf { it.isNotBlank() }
            ?.let { book.latestChapterTitle = it }
        analyzeRule.getString(rule.coverUrl, isUrl = true).takeIf { it.isNotBlank() }
            ?.let { book.coverUrl = it }
        analyzeRule.getString(rule.tocUrl, isUrl = true).takeIf { it.isNotBlank() }
            ?.let { book.tocUrl = it }
        if (book.name.isBlank()) book.name = "未命名"
        return book
    }

    suspend fun chapters(book: Book): List<BookChapter> {
        val tocUrl = book.tocUrl.ifBlank { book.bookUrl }
        val analyzeUrl = AnalyzeUrl(
            mUrl = tocUrl,
            source = source,
            ruleData = book,
            coroutineContext = currentCoroutineContext(),
        )
        val response = analyzeUrl.getStrResponseAwait()
        val body = response.body ?: throw NoStackTraceException("目录页内容为空")
        val baseUrl = response.url
        val rule = source.getTocRule()
        val analyzeRule = AnalyzeRule(book, source)
        analyzeRule.setContent(body)
        analyzeRule.setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(baseUrl)
        analyzeRule.setCoroutineContext(currentCoroutineContext())

        val (listRule, reverse) = parseRulePrefix(rule.chapterList)
            ?: throw NoStackTraceException("书源未配置目录规则")
        val chapters = ArrayList<BookChapter>()
        val visited = HashSet<String>()
        var currentUrl = tocUrl
        var currentBody = body
        var currentAnalyzeRule = analyzeRule
        var page = 0

        while (page++ < 100) {
            val items = currentAnalyzeRule.getElements(listRule)
            if (items.isEmpty()) {
                if (chapters.isEmpty()) throw NoStackTraceException("目录为空")
                break
            }
            items.forEach { item ->
                val title = currentAnalyzeRule.getString(rule.chapterName, item).trim()
                val url = currentAnalyzeRule.getString(rule.chapterUrl, item, isUrl = true)
                if (title.isNotBlank() && url.isNotBlank() && visited.add(url)) {
                    chapters.add(
                        BookChapter(
                            url = url,
                            title = title,
                            bookUrl = book.bookUrl,
                            isVip = currentAnalyzeRule.getString(rule.isVip, item).isNotBlank(),
                        )
                    )
                }
            }
            val nextUrl = currentAnalyzeRule.getString(rule.nextTocUrl, isUrl = true)
            if (nextUrl.isBlank() || nextUrl == currentUrl || !visited.add(nextUrl)) break
            kotlinx.coroutines.delay(400)
            val nextResponse = AnalyzeUrl(
                mUrl = nextUrl,
                source = source,
                ruleData = book,
                coroutineContext = currentCoroutineContext(),
            ).getStrResponseAwait()
            val nextBody = nextResponse.body ?: break
            currentUrl = nextResponse.url
            currentBody = nextBody
            currentAnalyzeRule = AnalyzeRule(book, source)
            currentAnalyzeRule.setContent(nextBody)
            currentAnalyzeRule.setBaseUrl(currentUrl)
            currentAnalyzeRule.setRedirectUrl(currentUrl)
            currentAnalyzeRule.setCoroutineContext(currentCoroutineContext())
        }

        if (reverse) chapters.reverse()
        chapters.forEachIndexed { index, chapter -> chapter.index = index }
        book.tocHtml = currentBody
        book.totalChapterNum = chapters.size
        book.latestChapterTitle = chapters.lastOrNull()?.title
        return chapters
    }

    suspend fun content(book: Book, chapter: BookChapter, allowVisibleChallenge: Boolean = true): String =
        contentDocument(book, chapter, allowVisibleChallenge).text

    /**
     * 视频源取流：contentRule 可能返回单个 URL、`名称::URL` 多行、或 JSON（resolutions/headers）。
     */
    suspend fun videoSource(book: Book, chapter: BookChapter): VideoSource {
        chapter.resourceUrl?.takeIf { it.isNotBlank() }?.let { return parseVideoSource(it) }
        val analyzeUrl = AnalyzeUrl(
            mUrl = chapter.url,
            source = source,
            ruleData = book,
            chapter = chapter,
            coroutineContext = currentCoroutineContext(),
        )
        val response = analyzeUrl.getStrResponseAwait()
        val body = response.body ?: throw NoStackTraceException("剧集页内容为空")
        val rule = source.getContentRule()
        val analyzeRule = AnalyzeRule(book, source)
        analyzeRule.setContent(body)
        analyzeRule.setBaseUrl(response.url)
        analyzeRule.setRedirectUrl(response.url)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        analyzeRule.setChapter(chapter)
        val raw = analyzeRule.getString(rule.content).trim()
        if (raw.isBlank()) throw NoStackTraceException("未获取到视频地址")
        return parseVideoSource(raw)
    }

    private fun parseVideoSource(raw: String): VideoSource {
        var content = raw.trim()
        var headers: Map<String, String>? = null
        if (content.startsWith("#BASE:")) {
            val index = content.indexOf('\n')
            if (index > 0) {
                val base = content.substring(6, index).trim()
                content = content.substring(index + 1).trim()
                if (base.isNotBlank()) headers = mapOf("Referer" to base)
            }
        }
        if (content.startsWith("{")) {
            val parsed = runCatching {
                GSON.fromJsonObject<VideoSource>(content).getOrNull()
            }.getOrNull()
            if (parsed != null && parsed.resolutions.any { it.url.isNotBlank() }) {
                return parsed.copy(
                    resolutions = parsed.resolutions.filter { it.url.isNotBlank() },
                    defaultIndex = parsed.defaultIndex.coerceAtLeast(0),
                )
            }
        }
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val listPairs = lines.filter { it.contains("::") }.mapNotNull { line ->
            val parts = line.split("::", limit = 2)
            if (parts.size == 2 && parts[1].startsWith("http")) {
                VideoResolution(name = parts[0].trim(), url = parts[1].trim())
            } else {
                null
            }
        }
        if (listPairs.isNotEmpty()) {
            return VideoSource(resolutions = listPairs, headers = headers)
        }
        val url = content.substringBefore('\n').trim()
        if (!url.startsWith("http")) throw NoStackTraceException("不是有效的视频地址：$url")
        return VideoSource(
            resolutions = listOf(VideoResolution(name = "默认", url = url)),
            headers = headers,
        )
    }

    suspend fun contentDocument(
        book: Book,
        chapter: BookChapter,
        allowVisibleChallenge: Boolean = true,
    ): ContentDocument {
        val analyzeUrl = AnalyzeUrl(
            mUrl = chapter.url,
            source = source,
            ruleData = book,
            chapter = chapter,
            coroutineContext = currentCoroutineContext(),
        )
        val response = analyzeUrl.getStrResponseAwait(allowVisibleChallenge = allowVisibleChallenge)
        val body = response.body ?: throw NoStackTraceException("正文页内容为空")
        val baseUrl = response.url
        val rule = source.getContentRule()
        val analyzeRule = AnalyzeRule(book, source)
        analyzeRule.setContent(body)
        analyzeRule.setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(baseUrl)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        analyzeRule.setChapter(chapter)

        val documents = ArrayList<ContentDocument>()

        fun appendPage(html: String, pageBaseUrl: String, pageRule: AnalyzeRule) {
            var pageContent = html
            rule.replaceRegex?.takeIf { it.isNotBlank() }?.let { replace ->
                pageContent = pageRule.getString(replace, pageContent)
            }
            documents.add(htmlToDocument(pageContent, pageBaseUrl, rule.imageStyle))
        }

        appendPage(analyzeRule.getString(rule.content), baseUrl, analyzeRule)
        var nextUrl = analyzeRule.getString(rule.nextContentUrl, isUrl = true)
        var page = 0
        while (nextUrl.isNotBlank() && nextUrl != chapter.url && page++ < 10) {
            val nextResponse = AnalyzeUrl(
                mUrl = nextUrl,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = currentCoroutineContext(),
            ).getStrResponseAwait(allowVisibleChallenge = allowVisibleChallenge)
            val nextBody = nextResponse.body ?: break
            val nextRule = AnalyzeRule(book, source)
            nextRule.setContent(nextBody)
            nextRule.setBaseUrl(nextResponse.url)
            nextRule.setRedirectUrl(nextResponse.url)
            nextRule.setCoroutineContext(currentCoroutineContext())
            nextRule.setChapter(chapter)
            appendPage(nextRule.getString(rule.content), nextResponse.url, nextRule)
            nextUrl = nextRule.getString(rule.nextContentUrl, isUrl = true)
        }

        val blocks = documents.flatMap { it.blocks }
        if (blocks.isEmpty()) throw NoStackTraceException("正文为空")
        val text = blocks.filterIsInstance<ContentBlock.Text>()
            .joinToString("\n\n") { it.text }
        return ContentDocument(text, blocks)
    }

    private fun htmlToDocument(content: String, baseUrl: String, imageStyle: String?): ContentDocument {
        if (!content.contains('<')) {
            val text = normalizeText(content)
            val blocks = splitParagraphs(text).map { ContentBlock.Text(it) }
            return ContentDocument(blocks.joinToString("\n\n") { it.text }, blocks)
        }
        val blockTags = setOf(
            "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6",
            "li", "tr", "section", "article", "blockquote", "pre",
        )
        val blocks = ArrayList<ContentBlock>()
        val buffer = StringBuilder()

        fun flush() {
            val text = normalizeText(buffer.toString())
            buffer.setLength(0)
            splitParagraphs(text).forEach { blocks.add(ContentBlock.Text(it)) }
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> buffer.append(node.wholeText)
                is Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag == "img") {
                        flush()
                        imageSource(node, baseUrl)?.let { (src, alt) ->
                            blocks.add(ContentBlock.Image(src, alt, imageStyle))
                        }
                        return
                    }
                    val block = tag in blockTags
                    if (block && buffer.isNotEmpty() && !buffer.endsWith("\n")) buffer.append('\n')
                    node.childNodes().forEach(::walk)
                    if (block && buffer.isNotEmpty() && !buffer.endsWith("\n")) buffer.append('\n')
                }
                else -> node.childNodes().forEach(::walk)
            }
        }

        val document = runCatching { Jsoup.parse(content, baseUrl) }
            .getOrElse { Jsoup.parse(content) }
        document.body().childNodes().forEach(::walk)
        flush()
        val text = blocks.filterIsInstance<ContentBlock.Text>()
            .joinToString("\n\n") { it.text }
        return ContentDocument(text, blocks)
    }

    private fun imageSource(node: Element, baseUrl: String): Pair<String, String?>? {
        val raw = listOf("src", "data-src", "data-original", "data-lazy-src", "data-url")
            .asSequence()
            .map { node.attr(it).trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        if (raw.startsWith("javascript", true)) return null
        val src = if (raw.startsWith("data:", true)) {
            raw
        } else {
            NetworkUtils.getAbsoluteURL(baseUrl, raw)
        }
        if (src.isBlank()) return null
        val alt = node.attr("alt").trim().takeIf { it.isNotBlank() }
        return src to alt
    }

    private fun splitParagraphs(text: String): List<String> =
        text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun normalizeText(value: String): String = value
        .replace("\r\n", "\n")
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n\n")
        .trim()

    private fun parseRulePrefix(rule: String?): Pair<String, Boolean>? {
        var value = rule?.trim().orEmpty()
        if (value.isBlank()) return null
        var reverse = false
        if (value.startsWith("-")) {
            reverse = true
            value = value.substring(1)
        } else if (value.startsWith("+")) {
            value = value.substring(1)
        }
        return value.trim() to reverse
    }
}
