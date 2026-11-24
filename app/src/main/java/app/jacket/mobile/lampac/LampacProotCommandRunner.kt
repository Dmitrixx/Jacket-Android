package app.jacket.mobile.lampac

import id.or.oo.pr.engine.ProotLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Use the same Debian Forky-slim base as the upstream Lampac runtime image.
 * A dedicated alias prevents collisions with older or user-created Debian containers.
 */
internal const val LAMPAC_DEBIAN_ALIAS = "lampac-debian"
internal const val LAMPAC_DEBIAN_OCI_IMAGE = "docker.io/library/debian:forky-slim"
internal const val LAMPAC_DEBIAN_ARM32_OCI_IMAGE = "docker.io/library/debian:trixie-slim"

internal fun lampacDebianInstallArguments(
    architecture: LampacRuntimeArchitecture = LampacRuntimeArchitecture.ARM64
): List<String> = listOf(
    "install",
    when (architecture) {
        LampacRuntimeArchitecture.ARM64 -> LAMPAC_DEBIAN_OCI_IMAGE
        LampacRuntimeArchitecture.ARM32 -> LAMPAC_DEBIAN_ARM32_OCI_IMAGE
    },
    "--override-alias",
    LAMPAC_DEBIAN_ALIAS
)

/**
 * Executes pr-cli operations without blocking the UI thread.
 *
 * Reads the non-blocking PTY in small chunks, emits complete UTF-8 log lines,
 * and closes the session on cancellation so the child receives a hangup.
 */
class LampacProotCommandRunner(
    private val commands: LampacProotCommands
) {
    suspend fun installDebian(
        architecture: LampacRuntimeArchitecture,
        onOutput: (String) -> Unit = {}
    ): Int = execute(lampacDebianInstallArguments(architecture), onOutput)

    suspend fun runAsRoot(
        command: String,
        onOutput: (String) -> Unit = {}
    ): Int {
        require(command.isNotBlank()) { "Команда Debian не задана" }
        require('\u0000' !in command) { "Команда содержит нулевой байт" }
        return execute(
            listOf("login", LAMPAC_DEBIAN_ALIAS, "--user", "root", "--", command),
            onOutput
        )
    }

    suspend fun execute(
        args: List<String>,
        onOutput: (String) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val session: ProotLauncher.Session = commands.start(args)
        val buffer = ByteArray(8 * 1024)
        val line = ByteArrayOutputStream(256)

        fun emitLine() {
            if (line.size() == 0) return
            onOutput(line.toByteArray().toString(StandardCharsets.UTF_8))
            line.reset()
        }

        try {
            while (true) {
                context.ensureActive()
                val count = session.read(buffer)
                when {
                    count > 0 -> {
                        for (index in 0 until count) {
                            val byte = buffer[index]
                            if (byte == '\n'.code.toByte()) {
                                emitLine()
                            } else if (line.size() < MAX_LOG_LINE_BYTES) {
                                line.write(byte.toInt())
                            }
                        }
                    }
                    count == 0 -> {
                        if (session.exitCode != null) break
                        delay(25)
                    }
                    else -> {
                        if (session.exitCode != null) break
                        delay(50)
                    }
                }
            }

            emitLine()
            while (session.exitCode == null) {
                context.ensureActive()
                delay(20)
            }
            session.exitCode ?: -1
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IOException("Ошибка команды PRoot: ${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            session.close()
        }
    }

    private companion object {
        const val MAX_LOG_LINE_BYTES = 16 * 1024
    }
}
