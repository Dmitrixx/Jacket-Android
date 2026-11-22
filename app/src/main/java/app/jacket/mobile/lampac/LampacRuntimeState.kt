package app.jacket.mobile.lampac

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        mutable.value = mutable.value.copy(
            phase = phase,
            message = message.replace(Regex("[\\r\\n\\t]+"), " ").take(240),
            progress = progress
        )
    }

    @Synchronized
    fun appendLog(value: String) {
        val clean = value.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(500)
        if (clean.isBlank()) return
        val rows = (mutable.value.logs + clean).takeLast(MAX_LOG_ROWS)
        mutable.value = mutable.value.copy(logs = rows)
    }

    fun clearLogs() {
        mutable.value = mutable.value.copy(logs = emptyList())
    }

    private const val MAX_LOG_ROWS = 80
}
