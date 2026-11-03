package io.legado.app.model

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Image(
        val src: String,
        val alt: String? = null,
        val style: String? = null,
    ) : ContentBlock
}

data class ContentDocument(
    val text: String,
    val blocks: List<ContentBlock>,
)
