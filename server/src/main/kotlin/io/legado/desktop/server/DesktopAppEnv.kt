package io.legado.desktop.server

import io.legado.desktop.core.CoreConfig
import io.legado.desktop.core.spi.AppEnv
import java.nio.file.Files
import java.nio.file.Path

class DesktopAppEnv(override val dataDir: Path) : AppEnv {
    override val cacheDir: Path = dataDir.resolve("cache")
    override val config: CoreConfig = CoreConfig()

    override fun ensureDirs() {
        Files.createDirectories(dataDir)
        Files.createDirectories(cacheDir)
    }
}
