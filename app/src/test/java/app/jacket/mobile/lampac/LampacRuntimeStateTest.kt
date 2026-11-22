package app.jacket.mobile.lampac

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LampacRuntimeStateTest {
    @Test
    fun concurrentStatusAndLogUpdatesPreserveBothPartsOfSnapshot() {
        LampacRuntimeState.clearLogs()

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        val logWriter = thread(start = true, name = "lampac-log-test") {
            ready.countDown()
            start.await()
            repeat(600) { index ->
                LampacRuntimeState.appendLog("entry-$index")
            }
        }
        val statusWriter = thread(start = true, name = "lampac-status-test") {
            ready.countDown()
            start.await()
            repeat(600) { index ->
                LampacRuntimeState.update(
                    phase = LampacRuntimePhase.RUNNING,
                    message = "status-$index",
                    progress = index % 101
                )
            }
        }

        ready.await()
        start.countDown()
        logWriter.join()
        statusWriter.join()

        val snapshot = LampacRuntimeState.state.value
        assertEquals(80, snapshot.logs.size)
        assertEquals("entry-599", snapshot.logs.last())
        assertEquals(LampacRuntimePhase.RUNNING, snapshot.phase)
        assertEquals("status-599", snapshot.message)
        assertEquals(599 % 101, snapshot.progress)
        assertTrue(snapshot.logs.all { it.startsWith("entry-") })

        LampacRuntimeState.clearLogs()
        LampacRuntimeState.update(LampacRuntimePhase.IDLE, "Встроенный Lampac ещё не установлен")
    }
}
