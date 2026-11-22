package app.jacket.mobile.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * Prevents stale network operations from publishing results or resetting state
 * after a newer login, mirror check, or explicit disconnect supersedes them.
 */
internal class OperationGenerationGate {
    private val generation = AtomicLong(0)

    fun begin(): Long = generation.incrementAndGet()

    fun invalidate(): Long = generation.incrementAndGet()

    fun isCurrent(value: Long): Boolean = generation.get() == value
}
