package app.jacket.mobile.lampac

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LampacRuntimePhase {
    IDLE,
    INSTALLING,
    INSTALLED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

data class LampacRuntimeSnapshot(
    val phase: LampacRuntimePhase = LampacRuntimePhase.IDLE,
    val message: String = "Встроенный Lampac ещё не установлен",
    val progress: Int? = null,
    val logs: List<String> = emptyList()
)

object LampacRuntimeState {
    private val mutable = MutableStateFlow(LampacRuntimeSnapshot())
    val state: StateFlow<LampacRuntimeSnapshot> = mutable.asStateFlow()

    fun update(
        phase: LampacRuntimePhase,
        message: String,
        progress: Int? = null
    ) {
        val cleanMessage = LampacLogSanitizer.sanitize(message).take(240)
        mutable.update { current ->
            current.copy(
                phase = phase,
                message = cleanMessage,
                progress = progress
            )
        }
    }

    fun appendLog(value: String) {
        val clean = LampacLogSanitizer.sanitize(value).take(500)
        if (clean.isBlank()) return
        mutable.update { current ->
            current.copy(logs = (current.logs + clean).takeLast(MAX_LOG_ROWS))
        }
    }

    fun clearLogs() {
        mutable.update { current -> current.copy(logs = emptyList()) }
    }

    private const val MAX_LOG_ROWS = 80
}
