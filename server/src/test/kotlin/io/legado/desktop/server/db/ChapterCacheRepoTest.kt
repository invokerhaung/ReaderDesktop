package io.legado.desktop.server.db

import io.legado.app.model.ContentBlock
import io.legado.app.model.ContentDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ChapterCacheRepoTest {

    @Test
    fun `save load clear and prune`() {
        val dataDir = Files.createTempDirectory("legado-cache-test")
        try {
            val repo = ChapterCacheRepo(Database(dataDir.resolve("legado.db")))
            val document = ContentDocument(
                text = "第一段\n\n第二段",
                blocks = listOf(
                    ContentBlock.Text("第一段"),
                    ContentBlock.Image("https://example.com/a.png", "插图", "FULL"),
                    ContentBlock.Text("第二段"),
                ),
            )
            repo.save("https://example.com/book", 3, document)

            val loaded = repo.get("https://example.com/book", 3, 1)
            assertEquals(document.text, loaded?.text)
            assertEquals(document.blocks, loaded?.blocks)
            val stats = repo.stats()
            assertEquals(1, stats.rows)
            assertTrue(stats.bytes > 0)
            assertNull(repo.get("https://example.com/book", 4, 1))

            repo.clear()
            assertEquals(0, repo.stats().rows)
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }
}
