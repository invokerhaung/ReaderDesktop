package io.legado.desktop.core

import io.legado.desktop.core.spi.AppEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class EngineTest {
    private val env = object : AppEnv {
        override val dataDir: Path = Path.of("build/test-data")
        override val cacheDir: Path = Path.of("build/test-cache")
        override val config: CoreConfig = CoreConfig()
        override fun ensureDirs() = Unit
    }

    @Test
    fun `ping returns pong`() {
        assertEquals("pong", Engine(env).ping())
    }
}
