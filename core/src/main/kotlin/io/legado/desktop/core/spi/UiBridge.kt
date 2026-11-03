package io.legado.desktop.core.spi

/**
 * core 与界面之间的回调，替代原 JsExtensions 里的 Activity / Dialog 调用。
 */
interface UiBridge {
    suspend fun confirm(message: String): Boolean
    suspend fun openUrl(url: String)
    suspend fun emit(event: String, payload: String)
}
