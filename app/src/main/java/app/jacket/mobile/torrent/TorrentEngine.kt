package app.jacket.mobile.torrent

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Immutable file metadata suitable for UI; contains no native handles. */
data class TorrentFileSnapshot(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
    val downloadedBytes: Long,
    val progress: Float,
    val priority: Int,
    val isSelected: Boolean
)

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
    val paused: Boolean,
    val files: List<TorrentFileSnapshot> = emptyList(),
    val selectedFileIndex: Int? = null,
    val selectedFileProgress: Float = 0f,
    val error: String? = null,
    val localStreamPort: Int? = null
)

data class TorrentStreamTarget(
    val id: String,
    val fileIndex: Int,
    val name: String,
    val sizeBytes: Long,
    val filePath: String,
    val progress: Float
)

/** Owns the single libtorrent session. JNI calls are kept on the service IO dispatcher. */
object TorrentEngine {
    @Volatile private var manager: SessionManager? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var alertListener: AlertListener? = null

    private val errors = ConcurrentHashMap<String, String>()
    @Volatile private var localStreamPort: Int? = null
    private val _snapshots = MutableStateFlow<List<TorrentSnapshot>>(emptyList())
    val snapshots: StateFlow<List<TorrentSnapshot>> = _snapshots.asStateFlow()

    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        if (manager?.isRunning == true) return

        val instance = SessionManager()
        val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.SAVE_RESUME_DATA.swig(),
                AlertType.SAVE_RESUME_DATA_FAILED.swig(),
                AlertType.METADATA_RECEIVED.swig(),
                AlertType.TORRENT_ERROR.swig(),
                AlertType.FILE_ERROR.swig(),
                AlertType.HASH_FAILED.swig()
            )

            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.SAVE_RESUME_DATA -> saveResumeAlert(alert as SaveResumeDataAlert)
                    AlertType.SAVE_RESUME_DATA_FAILED -> recordAlertError(alert)
                    AlertType.METADATA_RECEIVED -> {
                        val received = alert as MetadataReceivedAlert
                        runCatching {
                            val id = received.handle().infoHash().toHex()
                            val selected = TorrentQueueStore(appContext!!).get(id)?.selectedFileIndex
                            if (selected != null) applyFileSelection(received.handle(), selected)
                        }.onFailure { recordErrorFromAlert(alert, it.message ?: "Не удалось прочитать метаданные") }
                    }
                    AlertType.TORRENT_ERROR, AlertType.FILE_ERROR, AlertType.HASH_FAILED -> recordAlertError(alert)
                    else -> Unit
                }
            }
        }
        alertListener = listener
        instance.addListener(listener)

        val (downloadLimit, uploadLimit) = rateLimits(context)
        val settings = SettingsPack.defaultSettings()
            .downloadRateLimit(downloadLimit)
            .uploadRateLimit(uploadLimit)
        instance.start(org.libtorrent4j.SessionParams(settings))
        instance.startDht()
        manager = instance

        TorrentQueueStore(context).all().forEach { item ->
            runCatching { restoreItem(context, item) }
                .onFailure { errors[item.id] = it.message ?: "Не удалось восстановить загрузку" }
        }
        refresh()
    }

    @Synchronized
    private fun restoreItem(context: Context, item: QueuedTorrent) {
        val current = manager ?: return
        val resumeFile = resumeFile(context, item.id)
        if (resumeFile.isFile && resumeFile.length() > 0L) {
            val raw = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(resumeFile.readBytes()), error_code())
            raw.setSave_path(downloadDirectory(context).absolutePath)
            if (TorrentQueueStore(context).isPaused(item.id)) {
                raw.setFlags(raw.getFlags().or_(TorrentFlags.PAUSED))
            }
            current.swig().async_add_torrent(raw)
            return
        }

        if (!item.torrentPath.isNullOrBlank()) {
            val torrentFile = File(item.torrentPath)
            require(torrentFile.isFile) { "Файл .torrent для «${item.title}» не найден" }
            val info = TorrentInfo(torrentFile)
            val flags = torrent_flags_t()
            current.download(info, downloadDirectory(context), null, null, null, flags)
        } else {
            val magnet = item.magnetUri ?: throw IllegalStateException("В очереди отсутствует magnet и .torrent")
            current.download(magnet, downloadDirectory(context), torrent_flags_t())
        }
        if (TorrentQueueStore(context).isPaused(item.id)) findHandle(item.id)?.let(::pauseHandle)
    }

    @Synchronized
    fun add(context: Context, magnet: String, title: String) {
        start(context)
        val queued = TorrentQueueStore(context).add(magnet, title)
        if (findHandle(queued.id) == null) {
            manager?.download(queued.magnetUri!!, downloadDirectory(context), torrent_flags_t())
        }
        refresh()
    }

    /** Copies the provided torrent metadata into app-private storage before queueing it. */
    @Synchronized
    fun addTorrent(context: Context, source: File, title: String? = null): QueuedTorrent {
        require(source.isFile && source.length() in 1..TorrentQueueStore.MAX_TORRENT_BYTES) {
            "Файл .torrent отсутствует или превышает 8 МиБ"
        }
        val info = TorrentInfo(source)
        require(info.isValid) { "Файл не является корректным .torrent" }
        val id = info.infoHash().toHex().lowercase()
        val directory = torrentMetainfoDirectory(context)
        directory.mkdirs()
        val target = File(directory, "$id.torrent")
        if (source.canonicalFile != target.canonicalFile) source.copyTo(target, overwrite = true)
        val queued = TorrentQueueStore(context).addTorrentFile(target, id, title ?: info.name())
        start(context)
        if (findHandle(id) == null) {
            manager?.download(info, downloadDirectory(context), null, null, null, torrent_flags_t())
        }
        refresh()
        return queued
    }

    @Synchronized
    fun selectFile(context: Context, id: String, fileIndex: Int) {
        val item = TorrentQueueStore(context).get(id) ?: throw IllegalArgumentException("Загрузка не найдена")
        val handle = findHandle(item.id) ?: throw IllegalStateException("Торрент ещё подключается. Повторите через несколько секунд.")
        val info = handle.torrentFile() ?: throw IllegalStateException("Ожидаются метаданные раздачи")
        require(fileIndex in 0 until info.numFiles()) { "Неверный индекс файла" }
        require(!info.files().padFileAt(fileIndex)) { "Нельзя воспроизводить служебный pad-файл" }

        TorrentQueueStore(context).selectFile(id, fileIndex)
        applyFileSelection(handle, fileIndex)
        refresh()
    }

    @Synchronized
    private fun applyFileSelection(handle: TorrentHandle, fileIndex: Int) {
        require(handle.isValid) { "Торрент уже удалён" }
        val info = handle.torrentFile() ?: throw IllegalStateException("Метаданные раздачи ещё не получены")
        val files = info.files()
        require(fileIndex in 0 until files.numFiles())
        val priorities = Priority.array(Priority.IGNORE, files.numFiles())
        priorities[fileIndex] = Priority.TOP_PRIORITY
        handle.prioritizeFiles(priorities)
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)

        val first = files.pieceIndexAtFile(fileIndex)
        val last = files.lastPieceIndexAtFile(fileIndex)
        if (first >= 0 && last >= first) {
            // Keep startup and MP4 tail metadata responsive while the selected file streams.
            handle.setSequentialRange(first, last)
            val lastPriorityPiece = last
            for (piece in first until minOf(lastPriorityPiece + 1, first + 2)) {
                handle.piecePriority(piece, Priority.TOP_PRIORITY)
            }
            if (lastPriorityPiece >= first) handle.piecePriority(lastPriorityPiece, Priority.TOP_PRIORITY)
        }
    }

    @Synchronized
    fun pause(context: Context, id: String) {
        TorrentQueueStore(context).setPaused(id, true)
        findHandle(id)?.let(::pauseHandle)
        requestResumeData(id)
        refresh()
    }

    private fun pauseHandle(handle: TorrentHandle) {
        handle.setFlags(TorrentFlags.AUTO_MANAGED.inv(), TorrentFlags.AUTO_MANAGED)
        handle.pause()
    }

    @Synchronized
    fun resume(context: Context, id: String) {
        TorrentQueueStore(context).setPaused(id, false)
        val handle = findHandle(id)
        if (handle != null) {
            val selected = TorrentQueueStore(context).get(id)?.selectedFileIndex
            if (selected != null && handle.torrentFile() != null) applyFileSelection(handle, selected)
            handle.setFlags(TorrentFlags.AUTO_MANAGED, TorrentFlags.AUTO_MANAGED)
            handle.resume()
        }
        refresh()
    }

    /** Removes only the task from the queue. The downloaded data is preserved. */
    @Synchronized
    fun remove(context: Context, id: String, deleteFiles: Boolean = false) {
        val item = TorrentQueueStore(context).get(id)
        val handle = findHandle(id)
        // Capture metadata while the handle is still valid; this works for magnet and .torrent tasks.
        val info = handle?.let { runCatching { it.torrentFile() }.getOrNull() }
            ?: item?.torrentPath?.let { runCatching { TorrentInfo(File(it)) }.getOrNull() }
        val base = downloadDirectory(context).canonicalFile
        if (deleteFiles && info != null) {
            val storage = info.files()
            for (i in 0 until storage.numFiles()) {
                if (storage.padFileAt(i)) continue
                val path = File(storage.filePath(i, base.absolutePath)).canonicalFile
                if (path.toPath().startsWith(base.toPath()) && path.isFile) path.delete()
            }
        }
        handle?.let { manager?.remove(it) }
        TorrentQueueStore(context).remove(id)
        resumeFile(context, id).delete()
        refresh()
    }

    @Synchronized
    fun setRateLimits(context: Context, downloadKbps: Int, uploadKbps: Int) {
        val down = downloadKbps.coerceAtLeast(0)
        val up = uploadKbps.coerceAtLeast(0)
        context.applicationContext.getSharedPreferences(RATE_PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DOWNLOAD_KBPS, down).putInt(KEY_UPLOAD_KBPS, up).apply()
        manager?.applySettings(SettingsPack().downloadRateLimit(kbpsToBytes(down)).uploadRateLimit(kbpsToBytes(up)))
    }

    fun rateLimits(context: Context): Pair<Int, Int> {
        val prefs = context.applicationContext.getSharedPreferences(RATE_PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DOWNLOAD_KBPS, 0) to prefs.getInt(KEY_UPLOAD_KBPS, 0)
    }

    private fun kbpsToBytes(value: Int): Int = if (value <= 0) 0 else (value.toLong() * 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    @Synchronized
    fun setLocalStreamPort(port: Int?) {
        localStreamPort = port?.takeIf { it in 8090..8099 }
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
        val rows = ArrayList<TorrentSnapshot>(vector.size)

        for (index in 0 until vector.size) {
            val handle = TorrentHandle(vector.get(index))
            if (!handle.isValid) continue
            try {
                val status = handle.status()
                val id = handle.infoHash().toHex()
                val queued = store.get(id)
                val paused = id in pausedIds
                if (paused) pauseHandle(handle)
                val info = handle.torrentFile()
                val storage = info?.files()
                val progress = if (info != null) handle.fileProgress() else longArrayOf()
                var priorities = if (info != null) handle.filePriorities() else emptyArray()
                // A resume-data restore already includes metadata and will not emit METADATA_RECEIVED.
                val selectedIndex = queued?.selectedFileIndex
                if (info != null && selectedIndex != null &&
                    selectedIndex in priorities.indices &&
                    priorities[selectedIndex] != Priority.TOP_PRIORITY
                ) {
                    runCatching {
                        applyFileSelection(handle, selectedIndex)
                        priorities = handle.filePriorities()
                    }.onFailure { errors[id] = it.message ?: "Не удалось восстановить приоритет файла" }
                }
                val files = if (storage != null) {
                    (0 until storage.numFiles()).map { fileIndex ->
                        val size = storage.fileSize(fileIndex).coerceAtLeast(0L)
                        val downloaded = progress.getOrNull(fileIndex)?.coerceIn(0L, size) ?: 0L
                        TorrentFileSnapshot(
                            index = fileIndex,
                            path = storage.filePath(fileIndex),
                            sizeBytes = size,
                            downloadedBytes = downloaded,
                            progress = if (size == 0L) 0f else downloaded.toFloat() / size.toFloat(),
                            priority = priorities.getOrNull(fileIndex)?.swig()?.toInt() ?: 0,
                            isSelected = queued?.selectedFileIndex == fileIndex
                        )
                    }.filterNot { runCatching { storage.padFileAt(it.index) }.getOrDefault(true) }
                } else emptyList()
                val selectedProgress = files.firstOrNull { it.index == selectedIndex }?.progress ?: 0f
                rows += TorrentSnapshot(
                    id = id,
                    name = status.name().ifBlank { queued?.title ?: "Торрент ${id.take(10)}" },
                    progress = status.progress().coerceIn(0f, 1f),
                    downloadedBytes = status.totalWantedDone().coerceAtLeast(0),
                    totalBytes = status.totalWanted().coerceAtLeast(0),
                    downloadBytesPerSecond = status.downloadPayloadRate().coerceAtLeast(0),
                    uploadBytesPerSecond = status.uploadPayloadRate().coerceAtLeast(0),
                    seeds = status.numSeeds().coerceAtLeast(0),
                    peers = status.numPeers().coerceAtLeast(0),
                    state = status.state().name,
                    paused = paused,
                    files = files,
                    selectedFileIndex = selectedIndex,
                    selectedFileProgress = selectedProgress,
                    error = errors[id],
                    localStreamPort = localStreamPort
                )
            } catch (error: Exception) {
                errors[runCatching { handle.infoHash().toHex() }.getOrDefault("unknown")] =
                    error.message ?: "Ошибка чтения состояния торрента"
            }
        }
        _snapshots.value = rows.sortedBy { it.name.lowercase() }
    }

    /** Wait until every piece intersecting the requested file byte range is complete on disk. */
    fun awaitRange(id: String, fileIndex: Int, start: Long, length: Long, timeoutPerPieceMs: Long = 25_000L): File {
        require(start >= 0L && length > 0L)
        val handle = findHandle(id) ?: throw IllegalStateException("Торрент не найден")
        require(handle.isValid) { "Торрент больше не активен" }
        val info = handle.torrentFile() ?: throw IllegalStateException("Ожидаются метаданные торрента")
        val storage = info.files()
        require(fileIndex in 0 until storage.numFiles()) { "Неверный индекс файла" }
        val fileSize = storage.fileSize(fileIndex)
        require(start < fileSize && start + length <= fileSize) { "Запрошенный диапазон выходит за пределы файла" }
        val selected = TorrentQueueStore(appContext!!).get(id)?.selectedFileIndex
        if (selected != fileIndex) {
            selectFile(appContext!!, id, fileIndex)
        }
        val absoluteStart = storage.fileOffset(fileIndex) + start
        val absoluteEnd = absoluteStart + length - 1L
        val pieceLength = storage.pieceLength().toLong()
        val firstPiece = (absoluteStart / pieceLength).toInt()
        val lastPiece = (absoluteEnd / pieceLength).toInt()

        for (piece in firstPiece..lastPiece) {
            val began = System.currentTimeMillis()
            while (!handle.havePiece(piece)) {
                if (TorrentQueueStore(appContext!!).isPaused(id)) {
                    throw IllegalStateException("Загрузка приостановлена")
                }
                if (System.currentTimeMillis() - began > timeoutPerPieceMs) {
                    throw java.net.SocketTimeoutException("Ожидание части торрента превысило 25 секунд")
                }
                Thread.sleep(120)
            }
        }
        val root = File(handle.savePath()).canonicalFile
        val file = File(storage.filePath(fileIndex, root.absolutePath)).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "Путь файла выходит за каталог загрузки" }
        require(file.isFile && file.length() >= start + length) { "Файл ещё не записан на диск" }
        return file
    }

    fun target(id: String, fileIndex: Int): TorrentStreamTarget {
        val handle = findHandle(id) ?: throw IllegalStateException("Торрент не найден")
        val info = handle.torrentFile() ?: throw IllegalStateException("Метаданные ещё не получены")
        val storage = info.files()
        require(fileIndex in 0 until storage.numFiles())
        val size = storage.fileSize(fileIndex)
        val progress = handle.fileProgress().getOrNull(fileIndex) ?: 0L
        return TorrentStreamTarget(
            id = id,
            fileIndex = fileIndex,
            name = storage.fileName(fileIndex),
            sizeBytes = size,
            filePath = storage.filePath(fileIndex),
            progress = if (size <= 0L) 0f else (progress.toFloat() / size.toFloat()).coerceIn(0f, 1f)
        )
    }

    @Synchronized
    fun stop() {
        val current = manager ?: return
        saveAllResumeData()
        try { Thread.sleep(900) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        current.removeListener(alertListener)
        current.stop()
        manager = null
        alertListener = null
        localStreamPort = null
        _snapshots.value = emptyList()
    }

    @Synchronized
    fun saveAllResumeData() {
        val current = manager ?: return
        val vector = current.swig().get_torrents()
        for (index in 0 until vector.size) {
            val handle = TorrentHandle(vector.get(index))
            if (handle.isValid) runCatching { handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
                .onFailure { errors[handle.infoHash().toHex()] = it.message ?: "Не удалось сохранить состояние" }
        }
    }

    private fun requestResumeData(id: String) {
        findHandle(id)?.let { handle ->
            runCatching { handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
                .onFailure { errors[id] = it.message ?: "Не удалось сохранить продолжение" }
        }
    }

    private fun saveResumeAlert(alert: SaveResumeDataAlert) {
        runCatching {
            val id = alert.handle().infoHash().toHex()
            val context = appContext ?: return
            val file = resumeFile(context, id)
            file.parentFile?.mkdirs()
            val bytes = AddTorrentParams.writeResumeDataBuf(alert.params())
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
            errors.remove(id)
        }.onFailure { recordErrorFromAlert(alert, it.message ?: "Не удалось записать resume data") }
    }

    private fun recordAlertError(alert: Alert<*>) {
        recordErrorFromAlert(alert, alert.message().take(500))
    }

    private fun recordErrorFromAlert(alert: Alert<*>, message: String) {
        val id = runCatching {
            (alert as? org.libtorrent4j.alerts.TorrentAlert<*>)?.handle()?.infoHash()?.toHex()
        }.getOrNull() ?: "unknown"
        errors[id] = message.ifBlank { "Ошибка торрент-движка" }
    }

    private fun findHandle(id: String): TorrentHandle? {
        val current = manager ?: return null
        val vector = current.swig().get_torrents()
        for (index in 0 until vector.size) {
            val handle = TorrentHandle(vector.get(index))
            if (runCatching { handle.isValid && handle.infoHash().toHex().equals(id, true) }.getOrDefault(false)) {
                return handle
            }
        }
        return null
    }

    fun resumeFile(context: Context, id: String): File = File(File(context.filesDir, "torrent-resume"), "$id.fastresume")
    private fun torrentMetainfoDirectory(context: Context): File = File(context.filesDir, "torrent-metainfo").apply { mkdirs() }

    fun downloadDirectory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Downloads").apply { mkdirs() }

    private const val RATE_PREFS = "jacket_torrent_settings"
    private const val KEY_DOWNLOAD_KBPS = "download_kbps"
    private const val KEY_UPLOAD_KBPS = "upload_kbps"
}
