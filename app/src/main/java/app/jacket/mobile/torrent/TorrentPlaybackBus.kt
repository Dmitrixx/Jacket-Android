package app.jacket.mobile.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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

data class TorrentPlaybackFileOption(
    val index: Int,
    val name: String,
    val sizeBytes: Long
)

data class TorrentPlaybackSelectionRequest(
    val torrentId: String,
    val title: String,
    val files: List<TorrentPlaybackFileOption>
)

object TorrentPlaybackBus {
    private val selectionLock = Any()
    private var pendingChoice: Pair<String, CompletableDeferred<Int?>>? = null

    private val _active = MutableStateFlow<StreamPlaybackRequest?>(null)
    val active: StateFlow<StreamPlaybackRequest?> = _active.asStateFlow()

    private val _fileSelection = MutableStateFlow<TorrentPlaybackSelectionRequest?>(null)
    val fileSelection: StateFlow<TorrentPlaybackSelectionRequest?> = _fileSelection.asStateFlow()

    fun publish(request: StreamPlaybackRequest?) {
        _active.value = request
    }

    /**
     * Wait for a user choice when a torrent contains several playable videos.
     * A timeout or dismissal cancels autoplay rather than silently choosing an
     * arbitrary episode from a season pack.
     */
    suspend fun awaitFileChoice(
        torrentId: String,
        title: String,
        files: List<TorrentPlaybackFileOption>,
        timeoutMs: Long = 120_000L
    ): Int? {
        require(files.isNotEmpty()) { "Нет видеофайлов для выбора" }
        val deferred = CompletableDeferred<Int?>()
        synchronized(selectionLock) {
            pendingChoice?.second?.complete(null)
            pendingChoice = torrentId to deferred
            _fileSelection.value = TorrentPlaybackSelectionRequest(torrentId, title, files)
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            synchronized(selectionLock) {
                if (pendingChoice?.second === deferred) {
                    pendingChoice = null
                    _fileSelection.value = null
                }
            }
        }
    }

    fun chooseFile(torrentId: String, fileIndex: Int) {
        synchronized(selectionLock) {
            val pending = pendingChoice ?: return
            val request = _fileSelection.value ?: return
            if (pending.first != torrentId || request.files.none { it.index == fileIndex }) return
            pendingChoice = null
            _fileSelection.value = null
            pending.second.complete(fileIndex)
        }
    }

    fun cancelFileSelection(torrentId: String) {
        synchronized(selectionLock) {
            val pending = pendingChoice ?: return
            if (pending.first != torrentId) return
            pendingChoice = null
            _fileSelection.value = null
            pending.second.complete(null)
        }
    }
}
