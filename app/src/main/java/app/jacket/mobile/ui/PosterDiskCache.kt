package app.jacket.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Bounded memory/disk cache for public artwork. No credentials or private media are stored here. */
object PosterDiskCache {
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_DISK_BYTES = 96L * 1024L * 1024L

    private val memory = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }
    private val locks = HashMap<String, Any>()

    fun load(context: Context, url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        val key = sha256(url)
        memory.get(key)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(key) { Any() } }
        return try {
            synchronized(lock) {
                memory.get(key)?.let { return@synchronized it }
                val directory = File(context.cacheDir, "poster-artwork").apply { mkdirs() }
                val target = File(directory, "$key.img")
                if (!target.isFile || target.length() <= 0L) download(url, target)
                val bitmap = if (target.isFile) BitmapFactory.decodeFile(target.absolutePath) else null
                if (bitmap != null) {
                    target.setLastModified(System.currentTimeMillis())
                    memory.put(key, bitmap)
                    trimDisk(directory)
                } else {
                    target.delete()
                }
                bitmap
            }
        } finally {
            synchronized(locks) { locks.remove(key) }
        }
    }

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "JacketAndroid/2.1 (artwork cache)")
            setRequestProperty("Accept", "image/*")
        }
        val temporary = File(target.parentFile, target.name + ".tmp")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Artwork HTTP $status")
            if (connection.getHeaderFieldLong("Content-Length", -1L) > MAX_IMAGE_BYTES) {
                throw IOException("Artwork exceeds cache item limit")
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) throw IOException("Artwork exceeds cache item limit")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } finally {
            temporary.delete()
            connection.disconnect()
        }
    }

    private fun trimDisk(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "img" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_DISK_BYTES) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
