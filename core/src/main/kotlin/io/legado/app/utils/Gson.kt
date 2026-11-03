package io.legado.app.utils

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import java.lang.reflect.Type

val INITIAL_GSON: Gson by lazy {
    GsonBuilder()
        .registerTypeAdapter(Int::class.java, IntJsonDeserializer())
        .registerTypeAdapter(String::class.java, StringJsonDeserializer())
        .create()
}

val GSON: Gson by lazy {
    INITIAL_GSON.newBuilder()
        .registerTypeAdapter(ExploreRule::class.java, tolerantRuleDeserializer<ExploreRule>())
        .registerTypeAdapter(SearchRule::class.java, tolerantRuleDeserializer<SearchRule>())
        .registerTypeAdapter(BookInfoRule::class.java, tolerantRuleDeserializer<BookInfoRule>())
        .registerTypeAdapter(TocRule::class.java, tolerantRuleDeserializer<TocRule>())
        .registerTypeAdapter(ContentRule::class.java, tolerantRuleDeserializer<ContentRule>())
        .setLenient()
        .create()
}

val GSONStrict: Gson by lazy { GsonBuilder().create() }

private inline fun <reified T> tolerantRuleDeserializer(): JsonDeserializer<T?> =
    JsonDeserializer { json, _, _ ->
        when {
            json.isJsonObject -> INITIAL_GSON.fromJson(json, T::class.java)
            json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, T::class.java)
            else -> null
        }
    }

class StringJsonDeserializer : JsonDeserializer<String?> {
    override fun deserialize(
        json: com.google.gson.JsonElement,
        typeOfT: Type?,
        context: com.google.gson.JsonDeserializationContext?,
    ): String? = when {
        json.isJsonPrimitive -> json.asString
        json.isJsonNull -> null
        else -> json.toString()
    }
}

class IntJsonDeserializer : JsonDeserializer<Int?> {
    override fun deserialize(
        json: com.google.gson.JsonElement,
        typeOfT: Type?,
        context: com.google.gson.JsonDeserializationContext?,
    ): Int? = when {
        json.isJsonPrimitive && json.asJsonPrimitive.isNumber -> json.asInt
        else -> null
    }
}

inline fun <reified T> genericType(): Type = object : TypeToken<T>() {}.type

inline fun <reified T> Gson.fromJsonObject(json: String?): Result<T> = runCatching {
    requireNotNull(json) { "json is null" }
    fromJson(json, T::class.java) as T
}

inline fun <reified T> Gson.fromJsonArray(json: String?): Result<List<T>> = runCatching {
    requireNotNull(json) { "json is null" }
    fromJson<List<T>>(json, object : TypeToken<List<T>>() {}.type)
}
