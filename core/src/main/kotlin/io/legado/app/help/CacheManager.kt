package io.legado.app.help

import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 0 内存缓存。后续替换为磁盘缓存时保持方法签名不变。
 */
object CacheManager {
    private val memory = ConcurrentHashMap<String, Any?>()

    fun putMemory(key: String, value: Any?) {
        if (value == null) memory.remove(key) else memory[key] = value
    }

    fun getFromMemory(key: String): Any? = memory[key]

    fun deleteMemory(key: String) {
        memory.remove(key)
    }

    fun put(key: String, value: Any?) = putMemory(key, value)

    fun get(key: String): String? = memory[key]?.toString()

    fun remove(key: String) = deleteMemory(key)

    fun clear() = memory.clear()
}
