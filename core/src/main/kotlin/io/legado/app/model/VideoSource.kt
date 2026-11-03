package io.legado.app.model

data class VideoResolution(
    var name: String = "",
    var url: String = "",
)

data class VideoSource(
    var resolutions: List<VideoResolution> = emptyList(),
    var headers: Map<String, String>? = null,
    var defaultIndex: Int = 0,
) {
    fun resolutionAt(index: Int): VideoResolution? =
        resolutions.getOrNull(index.coerceIn(0, (resolutions.size - 1).coerceAtLeast(0)))
            ?: resolutions.firstOrNull()
}
