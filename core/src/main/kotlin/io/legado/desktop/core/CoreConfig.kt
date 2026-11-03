package io.legado.desktop.core

data class CoreConfig(
    val userAgent: String = DEFAULT_USER_AGENT,
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 30_000,
    val jsTimeoutMs: Long = 10_000,
    val proxy: String? = null,
) {
    companion object {
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
