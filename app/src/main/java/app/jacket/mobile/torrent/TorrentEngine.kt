package app.jacket.mobile.torrent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File

/** Immutable UI snapshot; native handles never escape the engine. */
data class TorrentSnapshot(
    val id: String,
    val name: String,
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val downloadBytesPerSecond: Int,
    val uploadBytesPerSecond: Int,
    val seeds: Int,
    val peers: Int,
    val state: String,
    val paused: Boolean
)

/** Owns the single libtorrent session. All JNI calls are made from the service IO context. */
object TorrentEngine {
    @Volatile private var manager: SessionManager? = null
    @Volatile private var appContext: Context? = null
    private val _snapshots = MutableStateFlow<List<TorrentSnapshot>>(emptyList())
    val snapshots: StateFlow<List<TorrentSnapshot>> = _snapshots.asStateFlow()

    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        if (manager?.isRunning == true) return

        val instance = SessionManager()
        instance.start()
        instance.startDht()
        manager = instance

        TorrentQueueStore(context).all().forEach { item ->
            runCatching {
                instance.download(item.magnetUri, downloadDirectory(context), torrent_flags_t())
            }
        }
        refresh()
    }

    @Synchronized
    fun add(context: Context, magnet: String, title: String) {
        start(context)
        val queued = TorrentQueueStore(context).add(magnet, title)
        if (findHandle(queued.id) == null) {
            manager?.download(queued.magnetUri, downloadDirectory(context), torrent_flags_t())
        }
        refresh()
    }

    @Synchronized
    fun pause(context: Context, id: String) {
        TorrentQueueStore(context).setPaused(id, true)
        findHandle(id)?.let { handle ->
            handle.setFlags(TorrentFlags.AUTO_MANAGED.inv(), TorrentFlags.AUTO_MANAGED)
            handle.pause()
        }
        refresh()
    }

    @Synchronized
    fun resume(context: Context, id: String) {
        TorrentQueueStore(context).setPaused(id, false)
        findHandle(id)?.resume()
        refresh()
    }

    /** Removes the task from the client queue. Downloaded files are intentionally preserved. */
    @Synchronized
    fun remove(context: Context, id: String) {
        findHandle(id)?.let { handle -> manager?.remove(handle) }
        TorrentQueueStore(context).remove(id)
        refresh()
    }

    @Synchronized
    fun refresh() {
        val current = manager ?: run {
            _snapshots.value = emptyList()
            return
        }
        val context = appContext ?: return
        val store = TorrentQueueStore(context)
        val pausedIds = store.pausedIds()
        val vector = current.swig().get_torrents()
        val rows = ArrayList<TorrentSnapshot>(vector.size().toInt())

        for (index in 0 until vector.size().toInt()) {
            val handle = TorrentHandle(vector.get(index))
            if (!handle.isValid) continue
            runCatching {
                val status = handle.status()
                val id = handle.infoHash().toHex()
                val paused = id in pausedIds
                if (paused) {
                    handle.setFlags(TorrentFlags.AUTO_MANAGED.inv(), TorrentFlags.AUTO_MANAGED)
                    handle.pause()
                }
                rows += TorrentSnapshot(
                    id = id,
                    name = status.name().ifBlank { "Торрент ${id.take(10)}" },
                    progress = status.progress().coerceIn(0f, 1f),
                    downloadedBytes = status.totalWantedDone().coerceAtLeast(0),
                    totalBytes = status.totalWanted().coerceAtLeast(0),
                    downloadBytesPerSecond = status.downloadPayloadRate().coerceAtLeast(0),
                    uploadBytesPerSecond = status.uploadPayloadRate().coerceAtLeast(0),
                    seeds = status.numSeeds().coerceAtLeast(0),
                    peers = status.numPeers().coerceAtLeast(0),
                    state = status.state().name,
                    paused = paused
                )
            }
        }
        _snapshots.value = rows.sortedBy { it.name.lowercase() }
    }

    @Synchronized
    fun stop() {
        manager?.stop()
        manager = null
        _snapshots.value = emptyList()
    }

    private fun findHandle(id: String): TorrentHandle? {
        val current = manager ?: return null
        val vector = current.swig().get_torrents()
        for (index in 0 until vector.size().toInt()) {
            val handle = TorrentHandle(vector.get(index))
            if (runCatching { handle.isValid && handle.infoHash().toHex().equals(id, true) }.getOrDefault(false)) {
                return handle
            }
        }
        return null
    }

    fun downloadDirectory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Downloads").apply { mkdirs() }
}
