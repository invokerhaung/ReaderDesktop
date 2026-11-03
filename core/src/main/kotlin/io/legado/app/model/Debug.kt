package io.legado.app.model

import io.legado.app.utils.LogUtils

object Debug {
    fun log(vararg messages: Any?) {
        if (messages.isEmpty()) return
        LogUtils.d("Debug", messages.joinToString(" ") { it?.toString() ?: "null" })
    }
}
