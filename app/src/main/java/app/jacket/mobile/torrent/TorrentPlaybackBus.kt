package app.jacket.mobile.torrent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackSubtitle(
    val url: String,
    val mimeType: String,
    val language: String?,
    val label: String
)

/** Process-local hand-off from the foreground download service to the Compose player. */
data class StreamPlaybackRequest(
    val title: String,
    val url: String,
    val progressKey: String,
    val initialPositionMs: Long,
    val subtitles: List<PlaybackSubtitle> = emptyList()
)

object TorrentPlaybackBus {
    private val _active = MutableStateFlow<StreamPlaybackRequest?>(null)
    val active: StateFlow<StreamPlaybackRequest?> = _active.asStateFlow()

    fun publish(request: StreamPlaybackRequest?) {
        _active.value = request
    }
}
