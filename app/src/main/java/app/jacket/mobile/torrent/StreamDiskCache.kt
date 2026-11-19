package app.jacket.mobile.torrent

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.LinkedHashMap

/** Stable identity for one aligned byte segment of one torrent file. */
internal data class StreamCacheKey(
    val infoHash: String,
    val fileIndex: Int,
    val segmentIndex: Long
)

/**
 * A bounded LRU disk ring for recently served video segments.
 *
 * The cache stores only playback copies. Libtorrent's verified torrent files
 * remain untouched, so evicting a cache slot never invalidates resume data or
 * causes a previously downloaded torrent piece to be downloaded again.
 */
internal class StreamDiskCache(
    directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    val segmentBytes: Int = DEFAULT_SEGMENT_BYTES
) : Closeable {
    private data class Slot(val index: Int, val length: Int)

    private val slotCount: Int
    private val entries = LinkedHashMap<StreamCacheKey, Slot>(16, 0.75f, true)
    private val slotOwners: Array<StreamCacheKey?>
    private val cacheFile: File
    private val storage: RandomAccessFile
    private var closed = false

    var evictions: Long = 0L
        private set

    init {
        require(maxBytes >= segmentBytes.toLong()) { "Кэш должен содержать хотя бы один сегмент" }
        require(segmentBytes > 0) { "Размер сегмента должен быть положительным" }
        slotCount = (maxBytes / segmentBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(slotCount > 0) { "Недостаточный размер дискового кэша" }
        slotOwners = arrayOfNulls(slotCount)
        require(directory.mkdirs() || directory.isDirectory) { "Не удалось создать каталог дискового кэша" }
        cacheFile = File.createTempFile("jacket-stream-", ".ring", directory)
        storage = RandomAccessFile(cacheFile, "rw")
    }

    /** Returns a detached segment copy, or null when the requested segment is not cached. */
    @Synchronized
    @Throws(IOException::class)
    fun get(key: StreamCacheKey, expectedLength: Int): ByteArray? {
        checkOpen()
        if (expectedLength !in 1..segmentBytes) return null
        val slot = entries[key] ?: return null
        if (slot.length != expectedLength) return null
        val result = ByteArray(slot.length)
        storage.seek(slot.index.toLong() * segmentBytes)
        storage.readFully(result)
        return result
    }

    /** Stores one segment and evicts the least-recently-used slot when the ring is full. */
    @Synchronized
    @Throws(IOException::class)
    fun put(key: StreamCacheKey, data: ByteArray) {
        checkOpen()
        require(data.isNotEmpty() && data.size <= segmentBytes) { "Недопустимый размер сегмента" }

        val existing = entries[key]
        val slotIndex = existing?.index ?: allocateSlot()
        storage.seek(slotIndex.toLong() * segmentBytes)
        storage.write(data)

        val previousOwner = slotOwners[slotIndex]
        if (previousOwner != null && previousOwner != key) entries.remove(previousOwner)
        entries[key] = Slot(slotIndex, data.size)
        slotOwners[slotIndex] = key
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        entries.clear()
        slotOwners.fill(null)
        runCatching { storage.close() }
        runCatching { cacheFile.delete() }
    }

    private fun allocateSlot(): Int {
        if (entries.size < slotCount) {
            val free = slotOwners.indexOfFirst { it == null }
            if (free >= 0) return free
        }
        val leastRecent = entries.entries.iterator()
        if (!leastRecent.hasNext()) return 0
        val evicted = leastRecent.next()
        val slot = evicted.value.index
        entries.remove(evicted.key)
        slotOwners[slot] = null
        evictions++
        return slot
    }

    private fun checkOpen() {
        check(!closed) { "Дисковый кэш потока уже закрыт" }
    }

    companion object {
        const val DEFAULT_SEGMENT_BYTES = 256 * 1024
        const val DEFAULT_MAX_BYTES = 32L * 1024L * 1024L
    }
}
