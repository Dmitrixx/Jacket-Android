package app.jacket.mobile.lampac

import java.io.ByteArrayOutputStream

/**
 * Reassembles complete UTF-8 process-output lines before they reach the log
 * sanitizer. PTY reads can split a credential field at any byte boundary.
 */
internal class LampacOutputLineBuffer(
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
    private val onLine: (String) -> Unit
) {
    private val pending = ByteArrayOutputStream(minOf(maxLineBytes, 1024))
    private var truncated = false

    init {
        require(maxLineBytes > 0) { "Максимальная длина строки должна быть положительной" }
    }

    fun append(bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size) { "Некорректная длина буфера вывода" }
        for (index in 0 until count) {
            val value = bytes[index]
            if (value == NEW_LINE) {
                emit()
            } else if (pending.size() < maxLineBytes) {
                pending.write(value.toInt())
            } else {
                truncated = true
            }
        }
    }

    /** Flush a final unterminated line when the child process exits. */
    fun finish() {
        if (pending.size() > 0 || truncated) emit()
    }

    private fun emit() {
        var line = pending.toByteArray().toString(Charsets.UTF_8)
        if (line.endsWith("\r")) line = line.dropLast(1)
        if (truncated) line += " [line truncated]"
        pending.reset()
        truncated = false
        onLine(line)
    }

    private companion object {
        const val DEFAULT_MAX_LINE_BYTES = 16 * 1024
        val NEW_LINE: Byte = '\n'.code.toByte()
    }
}
