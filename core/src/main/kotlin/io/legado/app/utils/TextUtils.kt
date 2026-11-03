package io.legado.app.utils

object TextUtils {
    fun isEmpty(value: CharSequence?): Boolean = value.isNullOrEmpty()

    fun join(delimiter: CharSequence, elements: Iterable<*>): String =
        elements.joinToString(delimiter.toString()) { it?.toString() ?: "" }

    fun join(delimiter: CharSequence, vararg elements: Any?): String =
        elements.joinToString(delimiter.toString()) { it?.toString() ?: "" }
}
