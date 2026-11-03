package io.legado.app.data.entities

import com.google.gson.annotations.SerializedName

/**
 * 内容替换/净化规则，字段语义与上游 replace_rules 保持一致。
 */
data class ReplaceRule(
    var id: Long = 0L,
    var name: String = "",
    var group: String? = null,
    var pattern: String = "",
    var replacement: String = "",
    var scope: String? = null,
    var scopeTitle: Boolean = false,
    var scopeContent: Boolean = true,
    var excludeScope: String? = null,
    var isEnabled: Boolean = true,
    var isRegex: Boolean = true,
    var timeoutMillisecond: Long = 3000L,
    @SerializedName("sortOrder") var order: Int = 0,
)
