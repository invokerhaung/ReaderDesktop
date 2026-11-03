package io.legado.desktop.core.spi

/**
 * 书源仓库。骨架阶段以 Legado 原始 JSON 字符串存储，避免过早绑定表结构。
 */
interface BookSourceRepo {
    suspend fun all(): List<String>
    suspend fun get(key: String): String?
    suspend fun upsert(key: String, json: String)
    suspend fun delete(key: String)
    suspend fun setEnabled(key: String, enabled: Boolean)
}
