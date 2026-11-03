package io.legado.desktop.core.spi

import io.legado.desktop.core.CoreConfig
import java.nio.file.Path

/**
 * 平台环境抽象。core 内部不得直接使用 Android Context 或桌面全局单例。
 */
interface AppEnv {
    val dataDir: Path
    val cacheDir: Path
    val config: CoreConfig
    fun ensureDirs()
}
