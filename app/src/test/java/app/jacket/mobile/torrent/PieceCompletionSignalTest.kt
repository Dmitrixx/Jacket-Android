package app.jacket.mobile.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PieceCompletionSignalTest {
    @Test
    fun signalWakesWaiter() {
        val signal = PieceCompletionSignal()
        val observed = signal.generation()
        val ready = CountDownLatch(1)
        var result = false

        val waiter = thread(name = "piece-signal-test") {
            ready.countDown()
            result = signal.awaitChange(observed, TimeUnit.SECONDS.toNanos(2))
        }

        assertTrue(ready.await(1, TimeUnit.SECONDS))
        signal.signal()
        waiter.join(1_000)

        assertFalse(waiter.isAlive)
        assertTrue(result)
    }

    @Test
    fun signalBroadcastsToEveryWaitingRangeWorker() {
        val signal = PieceCompletionSignal()
        val observed = signal.generation()
        val waiters = 3
        val ready = CountDownLatch(waiters)
        val completed = CountDownLatch(waiters)
        repeat(waiters) { index ->
            thread(name = "piece-signal-test-$index") {
                ready.countDown()
                if (signal.awaitChange(observed, TimeUnit.SECONDS.toNanos(2))) {
                    completed.countDown()
                }
            }
        }

        assertTrue(ready.await(1, TimeUnit.SECONDS))
        signal.signal()
        assertTrue(completed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun signalBeforeWaitIsNotLost() {
        val signal = PieceCompletionSignal()
        val observed = signal.generation()
        signal.signal()

        assertTrue(signal.awaitChange(observed, 0L))
    }

    @Test
    fun unchangedGenerationTimesOutImmediatelyWhenNoBudgetRemains() {
        val signal = PieceCompletionSignal()
        assertFalse(signal.awaitChange(signal.generation(), 0L))
    }

    @Test
    fun lowRamDevicesUseLowerAutomaticConnectionLimit() {
        assertTrue(TorrentSessionPolicy.connectionsLimit(true) < TorrentSessionPolicy.connectionsLimit(false))
        assertTrue(TorrentSessionPolicy.connectionsLimit(true) > 0)
    }

    @Test
    fun manualConnectionLimitOverridesAutomaticDevicePolicy() {
        assertEquals(120, TorrentSessionPolicy.connectionsLimit(true, 120))
        assertEquals(350, TorrentSessionPolicy.connectionsLimit(false, 350))
    }

    @Test
    fun invalidManualConnectionLimitFallsBackToDevicePolicy() {
        assertEquals(80, TorrentSessionPolicy.connectionsLimit(true, 10))
        assertEquals(200, TorrentSessionPolicy.connectionsLimit(false, 501))
    }
}
