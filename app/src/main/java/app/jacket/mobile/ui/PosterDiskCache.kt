package app.jacket.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** Bounded memory/disk cache for public artwork. No credentials or private media are stored here. */
object PosterDiskCache {
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_DISK_BYTES = 96L * 1024L * 1024L
    private const val MAX_BITMAP_WIDTH = 1920
    private const val MAX_BITMAP_HEIGHT = 1440
    private const val MAX_REDIRECTS = 3

    private data class LockEntry(val monitor: Any = Any(), var users: Int = 0)

    private val memory = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    // Reference-counted entries avoid removing a monitor while another coroutine is waiting on it.
    private val locks = HashMap<String, LockEntry>()

    fun load(context: Context, url: String): Bitmap? {
        if (!isSafeHttpsUrl(url)) return null
        val key = sha256(url)
        memory.get(key)?.let { return it }
        val entry = synchronized(locks) {
            locks.getOrPut(key) { LockEntry() }.also { it.users++ }
        }
        return try {
            synchronized(entry.monitor) {
                memory.get(key)?.let { return@synchronized it }
                val directory = File(context.cacheDir, "poster-artwork").apply { mkdirs() }
                val target = File(directory, "$key.img")
                if (!target.isFile || target.length() <= 0L) download(url, target)
                val bitmap = if (target.isFile) decodeSampled(target) else null
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
            synchronized(locks) {
                entry.users--
                if (entry.users == 0 && locks[key] === entry) locks.remove(key)
            }
        }
    }

    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            sample < 128 &&
            (bounds.outWidth / sample > MAX_BITMAP_WIDTH ||
                bounds.outHeight / sample > MAX_BITMAP_HEIGHT)
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun download(initialUrl: String, target: File) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        var currentUrl = initialUrl
        var redirects = 0
        try {
            while (true) {
                if (!isSafeHttpsUrl(currentUrl)) throw IOException("Artwork redirect must use HTTPS")
                val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "JacketAndroid/2.2 (artwork cache)")
                    setRequestProperty("Accept", "image/*")
                }
                try {
                    val status = connection.responseCode
                    if (status in REDIRECT_CODES) {
                        if (redirects++ >= MAX_REDIRECTS) throw IOException("Too many artwork redirects")
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Artwork redirect has no Location")
                        currentUrl = URI(currentUrl).resolve(URI(location)).toString()
                        continue
                    }
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
                    return
                } finally {
                    connection.disconnect()
                }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun isSafeHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null
    }.getOrDefault(false)

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

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
