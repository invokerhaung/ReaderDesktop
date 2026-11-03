package io.legado.desktop.server.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.ReplaceEngine
import io.legado.app.help.http.CookieStore
import io.legado.app.model.ContentBlock
import io.legado.app.model.ContentDocument
import io.legado.app.model.SourceEngine
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.desktop.core.spi.CookieBridgeHolder
import io.legado.desktop.core.spi.WebviewHosts
import io.legado.desktop.core.text.ChineseConverter
import io.legado.desktop.server.ServerContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ApiPayload(val status: Int, val json: String)

private data class ApiEnvelope(
    val isSuccess: Boolean,
    val errorMsg: String? = null,
    val data: Any? = null,
)

class ApiRouter(private val context: ServerContext) {

    private val contentCache = ConcurrentHashMap<String, ContentDocument>()
    private val sourceMatcher = SourceMatcher(context)

    fun handle(
        method: String,
        uri: String,
        params: Map<String, List<String>>,
        body: String,
    ): ApiPayload {
        return try {
            when {
                uri == "/api/ping" -> ok(mapOf("pong" to true))
                uri == "/api/sources" && method == "GET" -> ok(context.sourceRepo.list())
                uri == "/api/sources" && method == "DELETE" -> {
                    val key = params["key"]?.firstOrNull() ?: error("缺少 key")
                    context.sourceRepo.delete(key)
                    ok(mapOf("deleted" to key))
                }
                uri == "/api/sources/import" && method == "POST" -> importSources(body)
                uri == "/api/sources/enabled" && method == "POST" -> {
                    val obj = JsonParser.parseString(body).asJsonObject
                    val key = obj.get("key").asString
                    val enabled = obj.get("enabled").asBoolean
                    context.sourceRepo.setEnabled(key, enabled)
                    ok(mapOf("key" to key, "enabled" to enabled))
                }
                uri == "/api/sources/delete" && method == "POST" -> {
                    val obj = JsonParser.parseString(body).asJsonObject
                    val key = obj.get("key").asString
                    context.sourceRepo.delete(key)
                    ok(mapOf("deleted" to key))
                }
                uri == "/api/sources/login" && method == "POST" -> sourceLogin(body)
                uri == "/api/sources/verify" && method == "POST" -> verifySourceHost(body)
                uri == "/api/sources/cookies" && method == "DELETE" -> clearSourceCookies(params)
                uri == "/api/sources/detail" && method == "GET" -> {
                    val key = params["key"]?.firstOrNull() ?: error("缺少 key")
                    val source = context.sourceRepo.get(key) ?: error("书源不存在")
                    ok(mapOf("json" to GSON.toJson(source)))
                }
                uri == "/api/sources/save" && method == "POST" -> saveSource(body)
                uri == "/api/sources/test" && method == "POST" -> testSource(body)
                uri == "/api/source-groups" && method == "GET" -> listSourceGroups()
                uri == "/api/source-groups/rename" && method == "POST" -> renameSourceGroup(body)
                uri == "/api/source-groups/delete" && method == "POST" -> deleteSourceGroup(body)
                uri == "/api/source-groups/create" && method == "POST" -> createSourceGroup(body)
                uri == "/api/sources/groups" && method == "POST" -> setSourceGroups(body)
                uri == "/api/explore" && method == "GET" -> exploreKinds(params)
                uri == "/api/explore" && method == "POST" -> exploreBooks(body)
                uri == "/api/search" && method == "POST" -> search(body)
                uri == "/api/books" && method == "GET" -> ok(displayBooks(context.bookRepo.list()))
                uri == "/api/books" && method == "POST" -> addBook(body)
                uri == "/api/books/by-url" && method == "POST" -> addBooksByUrl(body)
                uri == "/api/books" && method == "DELETE" -> {
                    val url = params["url"]?.firstOrNull() ?: error("缺少 url")
                    context.bookRepo.delete(url)
                    ok(mapOf("deleted" to url))
                }
                uri == "/api/books/chapters" && method == "GET" -> chapters(params)
                uri == "/api/books/chapters/refresh" && method == "POST" -> refreshChapters(body)
                uri == "/api/books/content" && method == "GET" -> content(params)
                uri == "/api/books/video" && method == "GET" -> video(params)
                uri == "/api/preview/open" && method == "POST" -> openPreview(body)
                uri == "/api/preview/session" && method == "GET" -> previewSession(params)
                uri == "/api/preview/content" && method == "GET" -> previewContent(params)
                uri == "/api/preview/video" && method == "GET" -> previewVideo(params)
                uri == "/api/preview/close" && method == "POST" -> {
                    val obj = JsonParser.parseString(body).asJsonObject
                    val id = obj.get("id")?.asString ?: error("缺少 id")
                    context.previewStore.remove(id)
                    ok(mapOf("closed" to id))
                }
                uri == "/api/books/progress" && method == "PUT" -> updateProgress(body)
                uri == "/api/books/switch" && method == "POST" -> switchBook(body)
                uri == "/api/bookmarks" && method == "GET" -> {
                    val url = params["url"]?.firstOrNull() ?: error("缺少 url")
                    ok(context.bookmarkRepo.list(url))
                }
                uri == "/api/bookmarks" && method == "POST" -> addBookmark(body)
                uri == "/api/bookmarks" && method == "DELETE" -> {
                    val id = params["id"]?.firstOrNull()?.toLongOrNull() ?: error("缺少 id")
                    context.bookmarkRepo.delete(id)
                    ok(mapOf("deleted" to id))
                }
                uri == "/api/replace-rules" && method == "GET" -> ok(context.replaceRepo.list())
                uri == "/api/replace-rules" && method == "POST" -> saveReplaceRule(body)
                uri == "/api/replace-rules" && method == "DELETE" -> {
                    val id = params["id"]?.firstOrNull()?.toLongOrNull() ?: error("缺少 id")
                    context.replaceRepo.delete(id)
                    ok(mapOf("deleted" to id))
                }
                uri == "/api/replace-rules/enabled" && method == "POST" -> {
                    val obj = JsonParser.parseString(body).asJsonObject
                    val id = obj.get("id")?.asLong ?: error("缺少 id")
                    val enabled = obj.get("enabled")?.asBoolean ?: error("缺少 enabled")
                    context.replaceRepo.setEnabled(id, enabled)
                    ok(mapOf("id" to id, "enabled" to enabled))
                }
                uri == "/api/replace-rules/import" && method == "POST" -> importReplaceRules(body)
                uri == "/api/replace-rules/test" && method == "POST" -> testReplaceRule(body)
                uri.startsWith("/api/float/") && method == "POST" ->
                    floatCommand(uri.removePrefix("/api/float/"), body)
                uri == "/api/settings" && method == "GET" -> ok(readSettings())
                uri == "/api/settings" && method == "PUT" -> saveSettings(body)
                uri == "/api/window-theme" && method == "POST" -> {
                    val obj = JsonParser.parseString(body).asJsonObject
                    val dark = obj.boolOrNull("dark") ?: true
                    CookieBridgeHolder.bridge?.setWindowTheme(dark)
                    ok(mapOf("dark" to dark))
                }
                uri == "/api/stats" && method == "GET" -> ok(context.stats())
                uri == "/api/cache" && method == "GET" -> ok(context.cacheStats())
                uri == "/api/cache" && method == "DELETE" -> {
                    val type = params["type"]?.firstOrNull() ?: "all"
                    context.clearCache(type)
                    if (type == "chapter" || type == "all") contentCache.clear()
                    ok(context.cacheStats())
                }
                else -> ApiPayload(404, GSON.toJson(ApiEnvelope(false, "not found: $uri")))
            }
        } catch (e: Exception) {
            ApiPayload(500, GSON.toJson(ApiEnvelope(false, e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun importSources(body: String): ApiPayload {
        val element = JsonParser.parseString(body)
        val sources = ArrayList<BookSource>()
        val errors = ArrayList<String>()
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject
        if (obj != null && !obj.has("bookSourceUrl") && obj.has("url")) {
            val remote = obj.get("url").asString
            val text = fetchRemoteSourceText(remote)
            collectSources(JsonParser.parseString(text), sources, errors)
        } else {
            collectSources(element, sources, errors)
        }
        if (sources.isEmpty()) {
            val reason = errors.firstOrNull()
            error(if (reason == null) "没有可导入的书源" else "没有可导入的书源：$reason")
        }
        sources.forEach { context.sourceRepo.upsert(it) }
        return ok(mapOf("imported" to sources.size))
    }

    /**
     * 拉取远程书源。直连失败（Cloudflare 挑战等）时用 WebView2 打开页面取 JSON，再带 Cookie 重试。
     */
    private fun fetchRemoteSourceText(remote: String): String {
        val direct = runCatching { AnalyzeUrl(remote).getStrResponse().body.orEmpty() }
            .getOrDefault("")
        if (looksLikeSourceJson(direct)) return direct

        val bridge = CookieBridgeHolder.bridge?.takeIf { it.isAvailable() }
        if (bridge != null) {
            val result = runCatching {
                bridge.runTask(
                    url = remote,
                    mode = "html",
                    timeoutMs = 90_000,
                    javaScript = "document.body ? document.body.innerText : document.documentElement.outerHTML",
                    delayMs = 3_000,
                )
            }.getOrNull()
            val text = result?.value?.trim().orEmpty()
            if (looksLikeSourceJson(text)) return text
            val cookie = result?.cookies?.entries
                ?.joinToString("; ") { "${it.key}=${it.value}" }
                .orEmpty()
            if (cookie.isNotBlank()) {
                CookieStore.replaceCookie(remote, cookie)
                val retry = runCatching {
                    AnalyzeUrl(remote, headerMapF = mapOf("Cookie" to cookie))
                        .getStrResponse().body.orEmpty()
                }.getOrDefault("")
                if (looksLikeSourceJson(retry)) return retry
            }
        }
        if (direct.isBlank()) {
            error("导入链接无法访问，可能需要 Cloudflare 验证；可用浏览器打开后另存为文件导入")
        }
        error("导入链接返回的不是书源 JSON（可能被 Cloudflare 拦截），可用浏览器打开后另存为文件导入")
    }

    private fun looksLikeSourceJson(text: String): Boolean =
        text.contains("\"bookSourceUrl\"") || text.contains("\"bookSourceName\"")

    private fun collectSources(
        element: JsonElement,
        sources: MutableList<BookSource>,
        errors: MutableList<String>,
    ) {
        when {
            element.isJsonArray -> element.asJsonArray.forEach {
                collectSources(it, sources, errors)
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                if (!obj.has("bookSourceUrl")) {
                    errors += "缺少 bookSourceUrl"
                    return
                }
                GSON.fromJsonObject<BookSource>(obj.toString())
                    .onSuccess { sources += it }
                    .onFailure { errors += it.message ?: it.javaClass.simpleName }
            }
            else -> errors += "不是有效的书源 JSON"
        }
    }

    private fun listSourceGroups(): ApiPayload {
        val groups = context.sourceRepo.groups().map { (name, count) ->
            mapOf("name" to name, "count" to count)
        }
        return ok(
            mapOf(
                "groups" to groups,
                "ungrouped" to context.sourceRepo.ungroupedCount(),
            )
        )
    }

    private fun renameSourceGroup(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val from = obj.get("from")?.asString ?: error("缺少 from")
        val to = obj.stringOrNull("to")
        context.sourceRepo.renameGroup(from, to)
        return ok(mapOf("from" to from, "to" to to))
    }

    private fun deleteSourceGroup(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val name = obj.get("name")?.asString ?: error("缺少 name")
        context.sourceRepo.renameGroup(name, null)
        return ok(mapOf("deleted" to name))
    }

    private fun createSourceGroup(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val name = obj.get("name")?.asString?.trim() ?: error("缺少 name")
        if (name.isBlank()) error("分组名不能为空")
        val assignUngrouped = obj.boolOrNull("assignUngrouped") ?: true
        if (assignUngrouped) context.sourceRepo.assignGroupToUngrouped(name)
        return ok(mapOf("created" to name, "assignUngrouped" to assignUngrouped))
    }

    private fun setSourceGroups(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val key = obj.get("key")?.asString ?: error("缺少 key")
        val groups = obj.getAsJsonArray("groups")?.mapNotNull {
            it.takeIf { item -> !item.isJsonNull }?.asString?.trim()?.takeIf(String::isNotBlank)
        } ?: emptyList()
        context.sourceRepo.setGroups(key, groups)
        return ok(mapOf("key" to key, "groups" to groups))
    }

    private fun sourceLogin(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val key = obj.get("key")?.asString ?: error("缺少 key")
        val source = context.sourceRepo.get(key) ?: error("书源不存在")
        val bridge = CookieBridgeHolder.bridge ?: error("当前未通过桌面壳启动，无法打开登录窗口")
        if (!bridge.isAvailable()) error("WebView 桥不可用（需要通过桌面壳启动）")
        val url = obj.get("url")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            ?: source.bookSourceUrl
        val cookies = bridge.acquireCookies(
            url = url,
            mode = "login",
            timeoutMs = 300_000,
            title = "登录 ${source.bookSourceName}",
            userAgent = source.getHeaderMap(true)["User-Agent"],
        )
        if (cookies.isEmpty()) error("未获取到 Cookie，请确认已完成登录后再关闭窗口")
        val cookieText = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        CookieStore.replaceCookie(url, cookieText)
        return ok(mapOf("key" to key, "cookies" to cookies.size))
    }

    /**
     * 手动网页验证：打开可见 WebView 让用户完成 CF/登录，关闭后收集 Cookie，
     * 并把该 host 标记为“走 WebView”，后续章节会复用同一个 WebView2 会话。
     */
    private fun verifySourceHost(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val url = obj.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("缺少 url")
        val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString
            ?.takeIf { it.isNotBlank() } ?: "网页验证"
        val bridge = CookieBridgeHolder.bridge ?: error("当前未通过桌面壳启动，无法打开验证窗口")
        if (!bridge.isAvailable()) error("WebView 桥不可用（需要通过桌面壳启动）")
        val cookies = bridge.acquireCookies(
            url = url,
            mode = "login",
            timeoutMs = 300_000,
            title = "$title · 完成后关闭窗口",
            // 不要伪装 UA：手动验证必须使用 WebView2 原生指纹，CF 才认
            userAgent = null,
        )
        if (cookies.isEmpty()) error("未获取到验证 Cookie，请完成验证后再关闭窗口")
        CookieStore.replaceCookie(url, cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        WebviewHosts.mark(url)
        return ok(mapOf("url" to url, "cookies" to cookies.size))
    }

    private fun clearSourceCookies(params: Map<String, List<String>>): ApiPayload {
        val key = params["key"]?.firstOrNull() ?: error("缺少 key")
        val source = context.sourceRepo.get(key) ?: error("书源不存在")
        CookieStore.removeCookie(source.bookSourceUrl)
        return ok(mapOf("cleared" to key))
    }

    private fun saveSource(body: String): ApiPayload {
        val element = JsonParser.parseString(body)
        val sourceJson = if (element.isJsonObject && element.asJsonObject.has("json")) {
            element.asJsonObject.get("json")
        } else {
            element
        }
        if (!sourceJson.isJsonObject) error("书源 JSON 格式错误")
        val source = GSON.fromJsonObject<BookSource>(sourceJson.toString())
            .getOrNull() ?: error("书源 JSON 解析失败")
        if (source.bookSourceUrl.isBlank()) error("缺少 bookSourceUrl")
        context.sourceRepo.upsert(source)
        return ok(mapOf("saved" to source.getKey()))
    }

    private fun testSource(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val key = obj.get("key")?.asString ?: error("缺少 key")
        val keyword = obj.get("keyword")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            ?: "我的"
        val source = context.sourceRepo.get(key) ?: error("书源不存在")
        val started = System.currentTimeMillis()
        return try {
            val books = runBlocking {
                withTimeout(120_000) {
                    SourceEngine(source).search(keyword).onEach { it.infoHtml = null }
                }
            }
            ok(
                mapOf(
                    "ok" to true,
                    "elapsedMs" to (System.currentTimeMillis() - started),
                    "count" to books.size,
                    "first" to books.firstOrNull(),
                )
            )
        } catch (e: Exception) {
            ok(
                mapOf(
                    "ok" to false,
                    "elapsedMs" to (System.currentTimeMillis() - started),
                    "error" to (e.message ?: e.javaClass.simpleName),
                )
            )
        }
    }

    private fun exploreKinds(params: Map<String, List<String>>): ApiPayload {
        val key = params["key"]?.firstOrNull() ?: error("缺少 key")
        val source = context.sourceRepo.get(key) ?: error("书源不存在")
        val kinds = ArrayList<Map<String, String>>()
        var raw = source.exploreUrl
        if (!raw.isNullOrBlank() && (raw.startsWith("<js>", true) || raw.startsWith("@js:", true))) {
            val jsStr = if (raw.startsWith("@")) {
                raw.substring(4)
            } else {
                raw.substring(4, raw.lastIndexOf('<').coerceAtLeast(4))
            }
            raw = runBlocking {
                withContext(Dispatchers.IO) {
                    withTimeout(60_000) {
                        runCatching {
                            AnalyzeUrl(source.bookSourceUrl, source = source)
                                .evalJS(jsStr)?.toString()?.trim()
                        }.getOrNull() ?: ""
                    }
                }
            }
        }
        if (!raw.isNullOrBlank()) {
            runCatching {
                val element = JsonParser.parseString(raw)
                if (element.isJsonArray) {
                    element.asJsonArray.forEach { item ->
                        val entry = item.asJsonObject
                        val title = entry.get("title")?.takeIf { !it.isJsonNull }?.asString
                            ?: return@forEach
                        val url = entry.get("url")?.takeIf { !it.isJsonNull }?.asString
                            ?: return@forEach
                        if (url.isNotBlank()) kinds.add(mapOf("title" to title, "url" to url))
                    }
                }
            }
            if (kinds.isEmpty()) {
                raw.split(Regex("(&&|\\n)+")).forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) return@forEach
                    val index = trimmed.indexOf("::")
                    if (index > 0) {
                        val title = trimmed.substring(0, index).trim()
                            .removePrefix("BUTTON:")
                            .removePrefix("TITLE:")
                        val url = trimmed.substring(index + 2).trim()
                        if (url.isNotBlank()) kinds.add(mapOf("title" to title, "url" to url))
                    }
                }
            }
        }
        return ok(mapOf("key" to key, "name" to source.bookSourceName, "kinds" to kinds))
    }

    private fun exploreBooks(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val key = obj.get("key")?.asString ?: error("缺少 key")
        val url = obj.get("url")?.asString ?: error("缺少 url")
        val page = obj.get("page")?.asInt ?: 1
        val source = context.sourceRepo.get(key) ?: error("书源不存在")
        val books = runBlocking {
            withTimeout(180_000) {
                SourceEngine(source).explore(url, page).onEach { it.infoHtml = null }
            }
        }
        return ok(books)
    }

    /**
     * SSE 搜索：每个书源启动即推 source-start，完成推 source-results / source-error，全部结束推 done。
     */
    fun searchStream(
        keyword: String,
        sourceKey: String?,
        emit: (event: String, data: Any) -> Unit,
    ) {
        val sources = if (sourceKey.isNullOrBlank()) context.sourceRepo.enabled()
        else listOfNotNull(context.sourceRepo.get(sourceKey))
        if (sources.isEmpty()) {
            emit("done", mapOf("total" to 0, "error" to "没有启用的书源"))
            return
        }
        val total = AtomicInteger()
        runBlocking {
            coroutineScope {
                sources.forEach { source ->
                    launch(Dispatchers.IO) {
                        emit("source-start", mapOf("key" to source.getKey(), "name" to source.bookSourceName))
                        runCatching {
                            SourceEngine(source).search(keyword).onEach { it.infoHtml = null }
                        }.onSuccess { books ->
                            total.addAndGet(books.size)
                            emit("source-results", mapOf("key" to source.getKey(), "books" to books))
                        }.onFailure { e ->
                            emit(
                                "source-error",
                                mapOf(
                                    "key" to source.getKey(),
                                    "error" to (e.message ?: e.javaClass.simpleName),
                                ),
                            )
                        }
                    }
                }
            }
        }
        emit("done", mapOf("total" to total.get()))
    }

    private fun search(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val keyword = obj.get("keyword")?.asString ?: error("缺少 keyword")
        val sourceKey = obj.get("sourceKey")?.asString
        val sources = if (sourceKey.isNullOrBlank()) context.sourceRepo.enabled()
        else listOfNotNull(context.sourceRepo.get(sourceKey))
        if (sources.isEmpty()) error("没有启用的书源")

        val results = runBlocking {
            coroutineScope {
                sources.map { source ->
                    async(Dispatchers.IO) {
                        runCatching {
                            SourceEngine(source).search(keyword).onEach { it.infoHtml = null }
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        }
        return ok(results)
    }

    private fun addBook(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        var bookUrl = obj.get("bookUrl")?.asString ?: error("缺少 bookUrl")
        val sourceKey = obj.get("sourceKey")?.asString
        val matched = if (context.findSource(bookUrl, sourceKey) == null) {
            sourceMatcher.match(bookUrl)
        } else {
            null
        }
        if (matched != null) bookUrl = matched.bookUrl
        val source = context.findSource(bookUrl, sourceKey)
            ?: matched?.source
            ?: error("找不到对应书源")
        val existing = context.bookRepo.get(bookUrl)
        val providedName = obj.stringOrNull("name")?.takeIf { it.isNotBlank() }
        // 只抓一次详情页拿到真实目录地址；目录本身留到首次打开书时再拉取
        val fetched = runCatching {
            runBlocking { SourceEngine(source).bookInfo(bookUrl) }
        }
        val book = fetched.getOrNull() ?: run {
            val fallbackName = providedName
                ?: throw (fetched.exceptionOrNull() ?: error("详情页内容为空"))
            Book(
                name = fallbackName,
                bookUrl = bookUrl,
                origin = source.bookSourceUrl,
                originName = source.bookSourceName,
            )
        }
        if (book.name.isBlank() || (book.name == "未命名" && providedName != null)) {
            book.name = providedName ?: "未命名"
        }
        if (book.author.isBlank()) book.author = obj.stringOrNull("author") ?: ""
        book.type = BookType.fromSourceType(source.bookSourceType)
        book.coverUrl = book.coverUrl ?: obj.stringOrNull("coverUrl")
        book.intro = book.intro ?: obj.stringOrNull("intro")
        book.kind = book.kind ?: obj.stringOrNull("kind")
        book.wordCount = book.wordCount ?: obj.stringOrNull("wordCount")
        if (book.latestChapterTitle.isNullOrBlank()) {
            book.latestChapterTitle = obj.stringOrNull("latestChapterTitle")
                ?: obj.stringOrNull("lastChapter")
        }
        if (book.tocUrl.isBlank()) {
            book.tocUrl = obj.stringOrNull("tocUrl")
                ?.takeIf { it.isNotBlank() && it != bookUrl }
                ?: bookUrl
        }
        book.durChapterIndex = existing?.durChapterIndex ?: -1
        book.durChapterPos = existing?.durChapterPos ?: 0L
        book.totalChapterNum = existing?.totalChapterNum ?: 0
        if (book.latestChapterTitle.isNullOrBlank()) {
            book.latestChapterTitle = existing?.latestChapterTitle
        }
        if (book.tocUrl.isBlank()) book.tocUrl = existing?.tocUrl ?: bookUrl
        context.bookRepo.upsert(book)
        book.infoHtml = null
        book.tocHtml = null
        return ok(book)
    }

    private fun addBooksByUrl(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val raw = obj.stringOrNull("urls") ?: obj.stringOrNull("url") ?: error("缺少 url")
        val sourceKey = obj.stringOrNull("sourceKey")?.takeIf { it.isNotBlank() }
        if (sourceKey != null && context.sourceRepo.get(sourceKey) == null) {
            error("书源不存在")
        }
        val urls = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (urls.isEmpty()) error("请填写书籍网址")
        val results = urls.map { url ->
            runCatching { addOneBookByUrl(url, sourceKey) }.fold(
                onSuccess = { book -> mapOf("url" to url, "ok" to true, "book" to book) },
                onFailure = { e ->
                    mapOf("url" to url, "ok" to false, "error" to (e.message ?: e.javaClass.simpleName))
                },
            )
        }
        val success = results.count { it["ok"] == true }
        return ok(mapOf("total" to urls.size, "success" to success, "results" to results))
    }

    private fun addOneBookByUrl(url: String, forcedSourceKey: String? = null): Book {
        if (!url.startsWith("http", true)) error("地址格式不对：$url")
        val matched = sourceMatcher.match(url, forcedSourceKey) ?: error("未找到匹配书源")
        val bookUrl = matched.bookUrl
        val source = matched.source
        if (context.bookRepo.get(bookUrl) != null) error("已在书架")
        val book = runBlocking {
            withTimeout(180_000) { SourceEngine(source).bookInfo(bookUrl) }
        }
        book.bookUrl = bookUrl
        book.origin = source.bookSourceUrl
        book.originName = source.bookSourceName
        book.type = BookType.fromSourceType(source.bookSourceType)
        if (book.tocUrl.isBlank()) book.tocUrl = bookUrl
        val chapters = runBlocking {
            withTimeout(180_000) { SourceEngine(source).chapters(book) }
        }
        if (chapters.isEmpty()) error("目录为空")
        book.totalChapterNum = chapters.size
        book.latestChapterTitle = chapters.lastOrNull()?.title
        book.durChapterIndex = 0
        book.durChapterPos = 0L
        if (book.name.isNotBlank() && book.author.isNotBlank()) {
            context.bookRepo.findByNameAuthor(book.name, book.author)
                ?.takeIf { it.bookUrl != bookUrl }
                ?.let { old ->
                    val (index, pos) = migrateProgress(old, chapters)
                    book.durChapterIndex = index
                    book.durChapterPos = pos
                }
        }
        context.bookRepo.upsert(book)
        context.bookRepo.saveChapters(bookUrl, chapters)
        book.infoHtml = null
        book.tocHtml = null
        return book
    }

    /** 同名同作者换源时按章节标题迁移进度，近似 Android 的 Book.migrateTo。 */
    private fun migrateProgress(old: Book, chapters: List<BookChapter>): Pair<Int, Long> {
        if (old.durChapterIndex <= 0 || chapters.isEmpty()) return 0 to old.durChapterPos
        val oldChapters = context.bookRepo.chapters(old.bookUrl)
        val oldTitle = oldChapters.getOrNull(old.durChapterIndex)?.title
        if (!oldTitle.isNullOrBlank()) {
            val start = (old.durChapterIndex - 10).coerceAtLeast(0)
            val end = (old.durChapterIndex + 10).coerceAtMost(chapters.size - 1)
            if (start <= end) {
                val normalized = normalizeChapterName(oldTitle)
                val exact = (start..end)
                    .firstOrNull { normalizeChapterName(chapters[it].title) == normalized }
                if (exact != null) return exact to old.durChapterPos
                val oldNum = chapterNumber(oldTitle)
                if (oldNum != null) {
                    (start..end).firstOrNull { chapterNumber(chapters[it].title) == oldNum }
                        ?.let { return it to old.durChapterPos }
                }
            }
        }
        val index = if (old.totalChapterNum > 0) {
            (old.durChapterIndex.toLong() * chapters.size / old.totalChapterNum).toInt()
        } else {
            old.durChapterIndex
        }
        return index.coerceIn(0, chapters.size - 1) to old.durChapterPos
    }

    private fun normalizeChapterName(value: String): String =
        value.replace(Regex("[\\s\\p{Punct}，。！？、：；“”‘’（）《》【】—…·]+"), "").lowercase()

    private fun chapterNumber(value: String): Int? =
        Regex("\\d+").find(value)?.value?.toIntOrNull()

    private fun chapters(params: Map<String, List<String>>): ApiPayload {
        val bookUrl = params["url"]?.firstOrNull() ?: error("缺少 url")
        val book = context.bookRepo.get(bookUrl) ?: error("书籍不在书架")
        val cached = context.bookRepo.chapters(bookUrl)
        if (cached.isNotEmpty()) return ok(displayChapters(cached, book))
        val source = context.findSource(bookUrl) ?: error("找不到对应书源")
        val chapters = runBlocking { SourceEngine(source).chapters(book) }
        book.totalChapterNum = chapters.size
        book.latestChapterTitle = chapters.lastOrNull()?.title
        context.bookRepo.upsert(book)
        context.bookRepo.saveChapters(bookUrl, chapters)
        return ok(displayChapters(chapters, book))
    }

    private fun refreshChapters(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val bookUrl = obj.get("url")?.asString ?: error("缺少 url")
        val book = context.bookRepo.get(bookUrl) ?: error("书籍不在书架")
        val source = context.findSource(bookUrl) ?: error("找不到对应书源")
        val oldChapterUrl = runCatching {
            context.bookRepo.getChapter(bookUrl, book.durChapterIndex)?.url
        }.getOrNull()
        val chapters = runBlocking { SourceEngine(source).chapters(book) }
        if (chapters.isEmpty()) error("目录为空")
        book.totalChapterNum = chapters.size
        book.latestChapterTitle = chapters.lastOrNull()?.title
        if (!oldChapterUrl.isNullOrBlank()) {
            val newIndex = chapters.indexOfFirst { it.url == oldChapterUrl }
            if (newIndex >= 0) book.durChapterIndex = newIndex
        }
        context.bookRepo.upsert(book)
        context.bookRepo.saveChapters(bookUrl, chapters)
        contentCache.keys.removeIf { it.startsWith("$bookUrl#") }
        return ok(displayChapters(chapters, book))
    }

    private fun content(params: Map<String, List<String>>): ApiPayload {
        val bookUrl = params["url"]?.firstOrNull() ?: error("缺少 url")
        val index = params["index"]?.firstOrNull()?.toIntOrNull() ?: 0
        val book = context.bookRepo.get(bookUrl) ?: error("书籍不在书架")
        val chapter = context.bookRepo.getChapter(bookUrl, index) ?: error("章节不存在")
        val cacheKey = "$bookUrl#$index"
        val cacheEnabled = settingBool("cacheEnabled", true)
        val cacheDays = settingInt("cacheChapterDays", 30).coerceIn(1, 3650)
        var document = contentCache[cacheKey]
        if (document == null && cacheEnabled) {
            document = context.chapterCacheRepo.get(bookUrl, index, cacheDays)
            if (document != null) contentCache[cacheKey] = document
        }
        if (document == null) {
            val source = context.findSource(bookUrl) ?: error("找不到对应书源")
            val fetched = runCatching {
                runBlocking { SourceEngine(source).contentDocument(book, chapter) }
            }
            document = fetched.getOrNull()
            if (document == null && cacheEnabled) {
                // 网络失败时回退到过期缓存，支持离线阅读
                document = context.chapterCacheRepo.get(bookUrl, index, null)
            }
            if (document == null) {
                throw fetched.exceptionOrNull() ?: error("正文为空")
            }
            contentCache[cacheKey] = document
            if (cacheEnabled && fetched.isSuccess) {
                context.chapterCacheRepo.save(bookUrl, index, document)
                context.chapterCacheRepo.prune(cacheDays)
            }
        }
        val rules = activeReplaceRules()
        val script = scriptMode()
        val doc = document
        val title = applyScript(ReplaceEngine.applyTitle(chapter.title, book.name, book.origin, rules), script)
        val text = applyScript(ReplaceEngine.applyContent(doc.text, book.name, book.origin, rules), script)
        val blocks = doc.blocks.map { block ->
            when (block) {
                is ContentBlock.Text -> block.copy(
                    text = applyScript(
                        ReplaceEngine.applyContent(block.text, book.name, book.origin, rules),
                        script,
                    ),
                )
                is ContentBlock.Image -> block
            }
        }
        return ok(
            mapOf(
                "index" to index,
                "title" to title,
                "content" to text,
                "blocks" to blocks.map(::contentBlockJson),
            )
        )
    }

    private fun video(params: Map<String, List<String>>): ApiPayload {
        val bookUrl = params["url"]?.firstOrNull() ?: error("缺少 url")
        val index = params["index"]?.firstOrNull()?.toIntOrNull() ?: 0
        val resolutionIndex = params["resolution"]?.firstOrNull()?.toIntOrNull()
        val refresh = params["refresh"]?.firstOrNull() == "1"
        val book = context.bookRepo.get(bookUrl) ?: error("书籍不在书架")
        val chapter = context.bookRepo.getChapter(bookUrl, index) ?: error("章节不存在")
        val source = context.findSource(bookUrl) ?: error("找不到对应书源")
        if (refresh) chapter.resourceUrl = null
        val videoSource = runBlocking {
            withTimeout(180_000) { SourceEngine(source).videoSource(book, chapter) }
        }
        if (videoSource.resolutions.isEmpty()) error("未获取到视频地址")
        if (!refresh) {
            val encoded = GSON.toJson(videoSource)
            if (chapter.resourceUrl != encoded) {
                chapter.resourceUrl = encoded
                context.bookRepo.saveChapterResource(bookUrl, index, encoded)
            }
        }
        val chosen = videoSource.resolutionAt(resolutionIndex ?: videoSource.defaultIndex)
            ?: error("清晰度不存在")
        val kind = if (chosen.url.substringBefore('?').endsWith(".m3u8", true)) "hls" else "file"
        var drm = false
        if (kind == "hls") {
            drm = runCatching {
                val text = AnalyzeUrl(chosen.url, source = source).getStrResponse().body ?: ""
                Regex("widevine|playready|sample-aes|com\\.microsoft", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text.take(8000))
            }.getOrDefault(false)
        }
        return ok(
            mapOf(
                "bookUrl" to bookUrl,
                "index" to index,
                "title" to chapter.title,
                "kind" to if (drm) "drm" else kind,
                "drm" to drm,
                "url" to chosen.url,
                "name" to chosen.name,
                "defaultIndex" to videoSource.defaultIndex,
                "resolutions" to videoSource.resolutions.mapIndexed { i, item ->
                    mapOf("index" to i, "name" to item.name, "url" to item.url)
                },
            )
        )
    }

    private fun openPreview(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val bookUrl = obj.get("bookUrl")?.asString ?: error("缺少 bookUrl")
        val sourceKey = obj.stringOrNull("sourceKey") ?: obj.stringOrNull("origin")
        val source = context.findSource(bookUrl, sourceKey) ?: error("找不到对应书源")
        val providedName = obj.stringOrNull("name")?.takeIf { it.isNotBlank() }
        val fetched = runCatching {
            runBlocking { withTimeout(90_000) { SourceEngine(source).bookInfo(bookUrl) } }
        }.getOrNull()
        val book = fetched ?: Book(
            name = providedName ?: "未命名",
            bookUrl = bookUrl,
            origin = source.bookSourceUrl,
            originName = source.bookSourceName,
            tocUrl = bookUrl,
        )
        if (book.name.isBlank() || (book.name == "未命名" && providedName != null)) {
            book.name = providedName ?: "未命名"
        }
        if (book.author.isBlank()) book.author = obj.stringOrNull("author") ?: ""
        book.type = BookType.fromSourceType(source.bookSourceType)
        book.coverUrl = book.coverUrl ?: obj.stringOrNull("coverUrl")
        book.intro = book.intro ?: obj.stringOrNull("intro")
        book.kind = book.kind ?: obj.stringOrNull("kind")
        book.wordCount = book.wordCount ?: obj.stringOrNull("wordCount")
        if (book.latestChapterTitle.isNullOrBlank()) {
            book.latestChapterTitle = obj.stringOrNull("latestChapterTitle")
                ?: obj.stringOrNull("lastChapter")
        }
        if (book.tocUrl.isBlank()) {
            book.tocUrl = obj.stringOrNull("tocUrl")
                ?.takeIf { it.isNotBlank() && it != bookUrl }
                ?: bookUrl
        }
        book.durChapterIndex = 0
        book.durChapterPos = 0
        val chapters = runBlocking {
            withTimeout(120_000) { SourceEngine(source).chapters(book) }
        }
        if (chapters.isEmpty()) error("目录为空")
        val session = context.previewStore.create(source.getKey(), book, chapters)
        book.infoHtml = null
        book.tocHtml = null
        book.totalChapterNum = chapters.size
        book.latestChapterTitle = chapters.lastOrNull()?.title
        return ok(
            mapOf(
                "id" to session.id,
                "book" to book,
                "chapters" to displayChapters(chapters, book),
            )
        )
    }

    private fun previewSession(params: Map<String, List<String>>): ApiPayload {
        val session = skipPreview(params)
        return ok(
            mapOf(
                "id" to session.id,
                "book" to session.book,
                "chapters" to displayChapters(session.chapters, session.book),
            )
        )
    }

    private fun previewContent(params: Map<String, List<String>>): ApiPayload {
        val session = skipPreview(params)
        val index = params["index"]?.firstOrNull()?.toIntOrNull() ?: 0
        val chapter = session.chapters.getOrNull(index) ?: error("章节不存在")
        val document = session.contentCache[index] ?: run {
            val source = context.sourceRepo.get(session.sourceKey)
                ?: error("书源已被删除")
            val fetched = runBlocking {
                withTimeout(90_000) {
                    SourceEngine(source).contentDocument(session.book, chapter)
                }
            }
            session.contentCache[index] = fetched
            fetched
        }
        val rules = activeReplaceRules()
        val script = scriptMode()
        val title = applyScript(
            ReplaceEngine.applyTitle(chapter.title, session.book.name, session.book.origin, rules),
            script,
        )
        val text = applyScript(
            ReplaceEngine.applyContent(document.text, session.book.name, session.book.origin, rules),
            script,
        )
        val blocks = document.blocks.map { block ->
            when (block) {
                is ContentBlock.Text -> block.copy(
                    text = applyScript(
                        ReplaceEngine.applyContent(
                            block.text, session.book.name, session.book.origin, rules,
                        ),
                        script,
                    ),
                )
                is ContentBlock.Image -> block
            }
        }
        return ok(
            mapOf(
                "index" to index,
                "title" to title,
                "content" to text,
                "blocks" to blocks.map(::contentBlockJson),
            )
        )
    }

    private fun previewVideo(params: Map<String, List<String>>): ApiPayload {
        val session = skipPreview(params)
        val index = params["index"]?.firstOrNull()?.toIntOrNull() ?: 0
        val resolutionIndex = params["resolution"]?.firstOrNull()?.toIntOrNull()
        val refresh = params["refresh"]?.firstOrNull() == "1"
        val chapter = session.chapters.getOrNull(index) ?: error("章节不存在")
        val source = context.sourceRepo.get(session.sourceKey) ?: error("书源已被删除")
        if (refresh) session.videoCache.remove(index)
        val videoSource = session.videoCache[index] ?: run {
            val fetched = runBlocking {
                withTimeout(180_000) { SourceEngine(source).videoSource(session.book, chapter) }
            }
            session.videoCache[index] = fetched
            fetched
        }
        if (videoSource.resolutions.isEmpty()) error("未获取到视频地址")
        val chosen = videoSource.resolutionAt(resolutionIndex ?: videoSource.defaultIndex)
            ?: error("清晰度不存在")
        val kind = if (chosen.url.substringBefore('?').endsWith(".m3u8", true)) "hls" else "file"
        var drm = false
        if (kind == "hls") {
            drm = runCatching {
                val text = AnalyzeUrl(chosen.url, source = source).getStrResponse().body ?: ""
                Regex("widevine|playready|sample-aes|com\\.microsoft", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text.take(8000))
            }.getOrDefault(false)
        }
        return ok(
            mapOf(
                "bookUrl" to session.book.bookUrl,
                "index" to index,
                "title" to chapter.title,
                "kind" to if (drm) "drm" else kind,
                "drm" to drm,
                "url" to chosen.url,
                "name" to chosen.name,
                "defaultIndex" to videoSource.defaultIndex,
                "resolutions" to videoSource.resolutions.mapIndexed { i, item ->
                    mapOf("index" to i, "name" to item.name, "url" to item.url)
                },
            )
        )
    }

    private fun skipPreview(params: Map<String, List<String>>): io.legado.desktop.server.PreviewSession {
        val id = params["id"]?.firstOrNull() ?: error("缺少 id")
        return context.previewStore.get(id) ?: error("试看会话已过期，请重新打开")
    }

    private fun contentBlockJson(block: ContentBlock): Map<String, Any?> = when (block) {        is ContentBlock.Text -> mapOf("type" to "text", "text" to block.text)
        is ContentBlock.Image -> mapOf(
            "type" to "image",
            "src" to block.src,
            "alt" to block.alt,
            "style" to block.style,
        )
    }

    private fun switchBook(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val fromUrl = obj.get("fromUrl")?.asString ?: error("缺少 fromUrl")
        val toUrl = obj.get("toUrl")?.asString ?: error("缺少 toUrl")
        val sourceKey = obj.get("sourceKey")?.asString
        if (fromUrl == toUrl) error("目标地址与当前书籍相同")
        val oldBook = context.bookRepo.get(fromUrl) ?: error("原书籍不在书架")
        val source = context.findSource(toUrl, sourceKey) ?: error("找不到目标书源")
        val engine = SourceEngine(source)
        val newBook = runBlocking { engine.bookInfo(toUrl) }
        if (newBook.name.isBlank()) newBook.name = oldBook.name
        if (newBook.author.isBlank()) newBook.author = oldBook.author
        newBook.durChapterIndex = oldBook.durChapterIndex
        val chapters = runCatching { runBlocking { engine.chapters(newBook) } }.getOrDefault(emptyList())
        if (chapters.isEmpty()) error("目标书源目录为空，已取消换源")
        context.bookRepo.upsert(newBook)
        if (chapters.isNotEmpty()) {
            newBook.totalChapterNum = chapters.size
            newBook.latestChapterTitle = chapters.last().title
            context.bookRepo.upsert(newBook)
            context.bookRepo.saveChapters(newBook.bookUrl, chapters)
        }
        context.bookRepo.delete(oldBook.bookUrl)
        newBook.infoHtml = null
        newBook.tocHtml = null
        return ok(newBook)
    }

    private fun addBookmark(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val bookUrl = obj.get("bookUrl")?.asString ?: error("缺少 bookUrl")
        context.bookRepo.get(bookUrl) ?: error("书籍不在书架")
        val chapterIndex = obj.get("chapterIndex")?.asInt ?: 0
        val chapterTitle = obj.get("chapterTitle")?.asString ?: ""
        val mode = obj.get("mode")?.asString?.takeIf { it == "scroll" } ?: "paged"
        val position = obj.get("position")?.asInt ?: 0
        val excerpt = obj.get("excerpt")?.asString?.takeIf { it.isNotBlank() }
        val saved = context.bookmarkRepo.add(
            Bookmark(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                mode = mode,
                position = position,
                excerpt = excerpt,
            )
        )
        return ok(saved)
    }

    private fun saveReplaceRule(body: String): ApiPayload {
        val element = JsonParser.parseString(body)
        if (!element.isJsonObject) error("替换规则必须是 JSON 对象")
        val rule = parseReplaceRule(element.asJsonObject) ?: error("替换规则格式不正确或正则表达式无效")
        return ok(context.replaceRepo.save(rule))
    }

    private fun importReplaceRules(body: String): ApiPayload {
        val rules = ArrayList<ReplaceRule>()
        var skipped = 0
        fun collect(element: JsonElement, depth: Int = 0) {
            when {
                element.isJsonArray -> element.asJsonArray.forEach { collect(it, depth) }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    when {
                        depth < 2 && obj.has("url") -> {
                            val remote = obj.get("url").asString
                            val text = AnalyzeUrl(remote).getStrResponse().body
                                ?: error("远程替换规则为空")
                            collect(JsonParser.parseString(text), depth + 1)
                        }
                        obj.has("replaceRules") -> collect(obj.get("replaceRules"), depth)
                        else -> {
                            val rule = parseReplaceRule(obj)
                            if (rule != null) rules.add(rule) else skipped++
                        }
                    }
                }
            }
        }
        collect(JsonParser.parseString(body))
        if (rules.isEmpty()) error("没有可导入的替换规则")
        rules.forEach { context.replaceRepo.save(it) }
        return ok(mapOf("imported" to rules.size, "skipped" to skipped))
    }

    private fun testReplaceRule(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val pattern = obj.stringOrNull("pattern") ?: error("缺少 pattern")
        val isRegex = obj.boolOrNull("isRegex") ?: true
        if (isRegex && !ReplaceEngine.isValidPattern(pattern)) error("正则表达式无效")
        val rule = ReplaceRule(
            pattern = pattern,
            replacement = obj.stringOrNull("replacement") ?: "",
            isRegex = isRegex,
            timeoutMillisecond = obj.longOrNull("timeoutMillisecond") ?: 3000L,
        )
        val text = obj.stringOrNull("text") ?: ""
        val result = ReplaceEngine.replace(text, rule)
        return ok(mapOf("result" to result, "changed" to (result != text)))
    }

    private fun readSettings(): JsonObject {
        val result = JsonObject()
        result.addProperty("replaceEnabled", true)
        result.addProperty("chineseScript", ChineseConverter.OFF)
        context.settingsRepo.all().forEach { (key, raw) ->
            val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull()
            if (parsed != null) result.add(key, parsed) else result.addProperty(key, raw)
        }
        return result
    }

    private fun saveSettings(body: String): ApiPayload {
        val element = JsonParser.parseString(body)
        if (!element.isJsonObject) error("设置必须是 JSON 对象")
        val values = LinkedHashMap<String, String>()
        element.asJsonObject.entrySet().forEach { (key, value) ->
            if (key.isNotBlank()) values[key] = GSON.toJson(value)
        }
        context.settingsRepo.putAll(values)
        return ok(readSettings())
    }

    private fun settingBool(key: String, default: Boolean): Boolean {
        val raw = context.settingsRepo.get(key) ?: return default
        return runCatching { JsonParser.parseString(raw).asBoolean }.getOrDefault(default)
    }

    private fun settingInt(key: String, default: Int): Int {
        val raw = context.settingsRepo.get(key) ?: return default
        return runCatching { JsonParser.parseString(raw).asInt }.getOrDefault(default)
    }

    private fun activeReplaceRules(): List<ReplaceRule> {
        return if (settingBool("replaceEnabled", true)) context.replaceRepo.list() else emptyList()
    }

    private fun scriptMode(): String {
        val raw = context.settingsRepo.get("chineseScript") ?: return ChineseConverter.OFF
        return runCatching {
            ChineseConverter.normalizeMode(JsonParser.parseString(raw).asString)
        }.getOrDefault(ChineseConverter.OFF)
    }

    private fun applyScript(text: String, mode: String): String =
        if (mode == ChineseConverter.OFF) text else ChineseConverter.convert(text, mode)

    private fun displayChapters(chapters: List<BookChapter>, book: Book): List<BookChapter> {
        val rules = activeReplaceRules()
        val script = scriptMode()
        if (rules.isEmpty() && script == ChineseConverter.OFF) return chapters
        return chapters.map { chapter ->
            val replaced = ReplaceEngine.applyTitle(chapter.title, book.name, book.origin, rules)
            val title = applyScript(replaced, script)
            if (title == chapter.title) chapter else chapter.copy(title = title)
        }
    }

    private fun displayBooks(books: List<Book>): List<Book> {
        val rules = activeReplaceRules()
        val script = scriptMode()
        if (rules.isEmpty() && script == ChineseConverter.OFF) return books
        return books.map { book ->
            val latest = book.latestChapterTitle
            if (latest.isNullOrBlank()) {
                book
            } else {
                book.copy(
                    latestChapterTitle = applyScript(
                        ReplaceEngine.applyTitle(latest, book.name, book.origin, rules),
                        script,
                    ),
                )
            }
        }
    }

    private fun parseReplaceRule(obj: JsonObject): ReplaceRule? {
        val pattern = obj.stringOrNull("pattern") ?: obj.stringOrNull("regex") ?: return null
        if (pattern.isBlank()) return null
        val isRegex = obj.boolOrNull("isRegex") ?: !obj.has("regex")
        val rule = ReplaceRule(
            id = obj.longOrNull("id") ?: 0L,
            name = obj.stringOrNull("name") ?: obj.stringOrNull("replaceSummary") ?: "",
            group = obj.stringOrNull("group") ?: obj.stringOrNull("groupName"),
            pattern = pattern,
            replacement = obj.stringOrNull("replacement") ?: "",
            scope = obj.stringOrNull("scope") ?: obj.stringOrNull("useTo"),
            scopeTitle = obj.boolOrNull("scopeTitle") ?: false,
            scopeContent = obj.boolOrNull("scopeContent") ?: true,
            excludeScope = obj.stringOrNull("excludeScope"),
            isEnabled = obj.boolOrNull("isEnabled") ?: obj.boolOrNull("enable") ?: true,
            isRegex = isRegex,
            timeoutMillisecond = obj.longOrNull("timeoutMillisecond") ?: 3000L,
            order = obj.intOrNull("sortOrder") ?: obj.intOrNull("serialNumber") ?: 0,
        )
        if (rule.isRegex && !ReplaceEngine.isValidPattern(rule.pattern)) return null
        return if (rule.name.isBlank()) rule.copy(name = rule.pattern.take(30)) else rule
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asString }.getOrNull()
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asLong }.getOrNull()
    }

    private fun JsonObject.boolOrNull(key: String): Boolean? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asBoolean }.getOrNull()
    }

    private fun updateProgress(body: String): ApiPayload {
        val obj = JsonParser.parseString(body).asJsonObject
        val url = obj.get("url")?.asString ?: error("缺少 url")
        val index = obj.get("index")?.asInt ?: 0
        val position = obj.longOrNull("position")
        context.bookRepo.updateProgress(url, index, position)
        return ok(mapOf("url" to url, "index" to index, "position" to position))
    }

    /** 浮窗是桌面壳里的独立原生窗口，能力通过 WebView 桥转发。 */
    private fun floatCommand(action: String, body: String): ApiPayload {
        val bridge = CookieBridgeHolder.bridge?.takeIf { it.isAvailable() }
            ?: error("浮窗功能需要通过桌面端启动")
        val raw = bridge.desktopCommand(action, body.ifBlank { "{}" })
            ?: error("浮窗功能需要通过桌面端启动")
        val obj = JsonParser.parseString(raw).asJsonObject
        if (obj.boolOrNull("ok") != true) {
            error(obj.stringOrNull("error") ?: "浮窗操作失败")
        }
        return ok(obj)
    }

    private fun ok(data: Any?) = ApiPayload(200, GSON.toJson(ApiEnvelope(true, data = data)))
}
