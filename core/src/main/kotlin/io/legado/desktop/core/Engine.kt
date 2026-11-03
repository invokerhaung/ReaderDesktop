package io.legado.desktop.core

import io.legado.desktop.core.spi.AppEnv

/**
 * 引擎入口占位。Phase 1 会把 AnalyzeRule / WebBook 等接进来。
 */
class Engine(private val env: AppEnv) {
    val version: String = "0.1.0-SNAPSHOT"

    fun describe(): Map<String, String> = mapOf(
        "version" to version,
        "dataDir" to env.dataDir.toString(),
        "cacheDir" to env.cacheDir.toString(),
    )

    fun ping(): String = "pong"
}
