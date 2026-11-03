package io.legado.app.constant

/**
 * 与上游一致的位标志书籍类型，便于书源备份互通。
 */
object BookType {
    const val text = 1 shl 3          // 8
    const val updateError = 1 shl 4   // 16
    const val audio = 1 shl 5         // 32
    const val image = 1 shl 6         // 64
    const val webFile = 1 shl 7       // 128
    const val local = 1 shl 8         // 256
    const val archive = 1 shl 9       // 512
    const val notShelf = 1 shl 10     // 1024
    const val video = 1 shl 11        // 2048
    const val rss = 1 shl 12          // 4096

    fun fromSourceType(bookSourceType: Int): Int = when (bookSourceType) {
        BookSourceType.audio -> audio
        BookSourceType.image -> image
        BookSourceType.file -> webFile
        BookSourceType.video -> video
        BookSourceType.rss -> rss
        else -> text
    }
}
