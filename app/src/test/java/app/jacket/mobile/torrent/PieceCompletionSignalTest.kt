package app.jacket.mobile.torrent

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
    fun lowRamDevicesUseLowerConnectionLimit() {
        assertTrue(TorrentSessionPolicy.connectionsLimit(true) < TorrentSessionPolicy.connectionsLimit(false))
        assertTrue(TorrentSessionPolicy.connectionsLimit(true) > 0)
    }
}
