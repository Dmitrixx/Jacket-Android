package app.jacket.mobile.torrent

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Event-driven wake-up signal for torrent piece completion.
 *
 * The generation counter prevents a completion notification from being lost
 * between checking havePiece() and entering the condition wait.
 */
internal class PieceCompletionSignal {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var generation = 0L

    fun generation(): Long = lock.withLock { generation }

    fun signal() = lock.withLock {
        generation++
        changed.signalAll()
    }

    /** Returns true when a newer signal was observed, false on timeout. */
    fun awaitChange(observedGeneration: Long, timeoutNanos: Long): Boolean {
        if (timeoutNanos <= 0L) return generation() != observedGeneration
        return lock.withLock {
            var remaining = timeoutNanos
            while (generation == observedGeneration && remaining > 0L) {
                remaining = changed.awaitNanos(remaining)
            }
            generation != observedGeneration
        }
    }
}

/** Conservative connection ceilings for memory-constrained Android TV boxes. */
internal object TorrentSessionPolicy {
    fun connectionsLimit(isLowRamDevice: Boolean): Int = if (isLowRamDevice) 80 else 200
}
