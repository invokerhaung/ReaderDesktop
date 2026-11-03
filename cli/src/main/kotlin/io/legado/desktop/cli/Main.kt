package io.legado.desktop.cli

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.model.SourceEngine
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) = runBlocking {
    if (args.size < 2) {
        printHelp()
        return@runBlocking
    }
    val command = args[0]
    val sourcePath = args[1]
    val source = loadSource(sourcePath)
    val engine = SourceEngine(source)

    try {
        when (command) {
            "search" -> {
                val keyword = args.getOrNull(2) ?: error("缺少关键词")
                val books = engine.search(keyword)
                books.forEachIndexed { index, book ->
                    println("[${index + 1}] ${book.name} - ${book.author}")
                    println("    ${book.bookUrl}")
                }
                println("共 ${books.size} 条")
            }

            "info" -> {
                val bookUrl = args.getOrNull(2) ?: error("缺少书籍 URL")
                val book = engine.bookInfo(bookUrl)
                println("书名: ${book.name}")
                println("作者: ${book.author}")
                println("简介: ${book.intro ?: ""}")
                println("目录: ${book.tocUrl}")
            }

            "chapters" -> {
                val bookUrl = args.getOrNull(2) ?: error("缺少书籍 URL")
                val book = engine.bookInfo(bookUrl)
                val chapters = engine.chapters(book)
                chapters.forEach { chapter ->
                    println("[${chapter.index}] ${chapter.title}  ${chapter.url}")
                }
                println("共 ${chapters.size} 章")
            }

            "content" -> {
                val chapterUrl = args.getOrNull(2) ?: error("缺少章节 URL")
                val bookUrl = args.getOrNull(3) ?: chapterUrl
                val book = Book(bookUrl = bookUrl, tocUrl = bookUrl, name = "CLI")
                val chapter = io.legado.app.data.entities.BookChapter(
                    url = chapterUrl,
                    title = "",
                    bookUrl = bookUrl,
                )
                val content = engine.content(book, chapter)
                println(content)
            }

            "json-search" -> {
                val keyword = args.getOrNull(2) ?: error("缺少关键词")
                val books = engine.search(keyword).map {
                    mapOf(
                        "name" to it.name,
                        "author" to it.author,
                        "bookUrl" to it.bookUrl,
                        "coverUrl" to it.coverUrl,
                        "intro" to it.intro,
                        "lastChapter" to it.lastChapter,
                        "wordCount" to it.wordCount,
                    )
                }
                println(GSON.toJson(books))
            }

            "json-chapters" -> {
                val bookUrl = args.getOrNull(2) ?: error("缺少书籍 URL")
                val book = engine.bookInfo(bookUrl)
                val chapters = engine.chapters(book).map {
                    mapOf("index" to it.index, "title" to it.title, "url" to it.url)
                }
                println(GSON.toJson(chapters))
            }

            else -> printHelp()
        }
    } finally {
        // no persistent resources yet
    }
}

private fun loadSource(path: String): BookSource {
    val file = File(path)
    require(file.isFile) { "书源文件不存在: $path" }
    return GSON.fromJsonObject<BookSource>(file.readText())
        .getOrElse { throw IllegalArgumentException("书源 JSON 解析失败: ${it.message}") }
}

private fun printHelp() {
    println(
        """
        用法:
          legado-cli search   <source.json> <keyword>
          legado-cli info     <source.json> <bookUrl>
          legado-cli chapters <source.json> <bookUrl>
          legado-cli content  <source.json> <chapterUrl> [bookUrl]
          legado-cli json-search <source.json> <keyword>
        """.trimIndent()
    )
}
