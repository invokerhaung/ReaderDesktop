package io.legado.app.data.entities

import com.google.gson.annotations.SerializedName
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.constant.SourceType
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.JsExtensions
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlin.jvm.Transient

interface BaseSource : JsExtensions {
    var concurrentRate: String?
    var loginUrl: String?
    var loginUi: String?
    var header: String?
    var enabledCookieJar: Boolean?
    var enableDangerousApi: Boolean?
    var jsLib: String?

    fun getTag(): String

    fun getKey(): String

    fun getSourceType(): Int

    override fun getSource(): BaseSource? = this

    fun getHeaderMap(hasLoginHeader: Boolean = false): Map<String, String> = emptyMap()

    fun getVariable(): String

    fun put(key: String, value: String): String

    fun get(key: String): String
}

interface BaseBook : RuleDataInterface {
    var name: String
    var author: String
    var bookUrl: String
    var variable: String?
    var infoHtml: String?
    var tocHtml: String?
    var origin: String
    var originName: String
    var tocUrl: String
}

data class BookSource(
    var bookSourceUrl: String = "",
    var bookSourceName: String = "",
    var bookSourceGroup: String? = null,
    var bookSourceType: Int = BookSourceType.default,
    var bookUrlPattern: String? = null,
    var customOrder: Int = 0,
    var enabled: Boolean = true,
    var enabledExplore: Boolean = true,
    override var jsLib: String? = null,
    override var enabledCookieJar: Boolean? = true,
    override var enableDangerousApi: Boolean? = false,
    override var concurrentRate: String? = null,
    override var header: String? = null,
    override var loginUrl: String? = null,
    override var loginUi: String? = null,
    var loginCheckJs: String? = null,
    var coverDecodeJs: String? = null,
    var bookSourceComment: String? = null,
    var variableComment: String? = null,
    var lastUpdateTime: Long = 0,
    var respondTime: Long = 180000L,
    var weight: Int = 0,
    var exploreUrl: String? = null,
    var exploreScreen: String? = null,
    var exploreStyle: Int = 0,
    var ruleExplore: ExploreRule? = null,
    var searchUrl: String? = null,
    var ruleSearch: SearchRule? = null,
    var ruleBookInfo: BookInfoRule? = null,
    var ruleToc: TocRule? = null,
    var ruleContent: ContentRule? = null,
    @SerializedName("variable")
    var variableJson: String? = null,
) : BaseSource {

    @field:Transient
    private var headerMapCache: HashMap<String, String>? = null

    @field:Transient
    private var variableMapCache: HashMap<String, String>? = null

    override fun getTag(): String = bookSourceName

    override fun getKey(): String = bookSourceUrl

    override fun getSourceType(): Int =
        if (bookSourceType == BookSourceType.rss) SourceType.rss else SourceType.book

    override fun getHeaderMap(hasLoginHeader: Boolean): Map<String, String> {
        headerMapCache?.let { return it }
        val parsed = GSON.fromJsonObject<HashMap<String, String>>(header).getOrNull() ?: hashMapOf()
        headerMapCache = parsed
        return parsed
    }

    private fun variableMap(): HashMap<String, String> {
        variableMapCache?.let { return it }
        val parsed = GSON.fromJsonObject<HashMap<String, String>>(variableJson).getOrNull()
            ?: hashMapOf()
        variableMapCache = parsed
        return parsed
    }

    override fun getVariable(): String = GSON.toJson(variableMap())

    override fun put(key: String, value: String): String {
        variableMap()[key] = value
        variableJson = GSON.toJson(variableMap())
        return value
    }

    override fun get(key: String): String = variableMap()[key] ?: ""

    fun getSearchRule(): SearchRule = ruleSearch ?: SearchRule().also { ruleSearch = it }

    fun getExploreRule(): ExploreRule = ruleExplore ?: ExploreRule().also { ruleExplore = it }

    fun getBookInfoRule(): BookInfoRule = ruleBookInfo ?: BookInfoRule().also { ruleBookInfo = it }

    fun getTocRule(): TocRule = ruleToc ?: TocRule().also { ruleToc = it }

    fun getContentRule(): ContentRule = ruleContent ?: ContentRule().also { ruleContent = it }

    override fun hashCode(): Int = bookSourceUrl.hashCode()

    override fun equals(other: Any?): Boolean =
        other is BookSource && other.bookSourceUrl == bookSourceUrl
}

data class Book(
    override var name: String = "",
    override var author: String = "",
    override var bookUrl: String = "",
    override var origin: String = "",
    override var originName: String = "",
    override var tocUrl: String = "",
    override var variable: String? = null,
    override var infoHtml: String? = null,
    override var tocHtml: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    var kind: String? = null,
    var wordCount: String? = null,
    var latestChapterTitle: String? = null,
    var totalChapterNum: Int = 0,
    var durChapterIndex: Int = 0,
    var durChapterPos: Long = 0L,
    var type: Int = BookType.text,
) : BaseBook {

    @field:Transient
    override val variableMap: HashMap<String, String> =
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: hashMapOf()

    override fun putBigVariable(key: String, value: String?) = Unit

    override fun getBigVariable(key: String): String? = null

    override fun putVariable(key: String, value: String?): Boolean {
        val changed = super.putVariable(key, value)
        variable = GSON.toJson(variableMap)
        return changed
    }
}

data class BookChapter(
    var url: String = "",
    var title: String = "",
    var isVolume: Boolean = false,
    var bookUrl: String = "",
    var index: Int = 0,
    var isVip: Boolean = false,
    var isPay: Boolean = false,
    var resourceUrl: String? = null,
    /** 桌面端扩展：该章是否已有本地正文缓存，不写入 chapters 表。 */
    var cached: Boolean = false,
    var tag: String? = null,
    var variable: String? = null,
) : RuleDataInterface {

    @field:Transient
    override val variableMap: HashMap<String, String> =
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: hashMapOf()

    override fun putBigVariable(key: String, value: String?) = Unit

    override fun getBigVariable(key: String): String? = null

    override fun putVariable(key: String, value: String?): Boolean {
        val changed = super.putVariable(key, value)
        variable = GSON.toJson(variableMap)
        return changed
    }

    override fun hashCode(): Int = url.hashCode()

    override fun equals(other: Any?): Boolean = other is BookChapter && other.url == url
}

data class SearchBook(
    override var name: String = "",
    override var author: String = "",
    override var bookUrl: String = "",
    override var origin: String = "",
    override var originName: String = "",
    override var tocUrl: String = "",
    override var variable: String? = null,
    override var infoHtml: String? = null,
    override var tocHtml: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    var kind: String? = null,
    var lastChapter: String? = null,
    var wordCount: String? = null,
    var type: Int = 0,
) : BaseBook {

    @field:Transient
    override val variableMap: HashMap<String, String> =
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: hashMapOf()

    override fun putBigVariable(key: String, value: String?) = Unit

    override fun getBigVariable(key: String): String? = null

    override fun putVariable(key: String, value: String?): Boolean {
        val changed = super.putVariable(key, value)
        variable = GSON.toJson(variableMap)
        return changed
    }
}
