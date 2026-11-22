package app.jacket.mobile.ui

import java.util.concurrent.atomic.AtomicLong

/** Invalidates late results from a cancelled or replaced asynchronous operation. */
internal class OperationGenerationGate {
    private val generation = AtomicLong(0)

    fun begin(): Long = generation.incrementAndGet()

    fun invalidate(): Long = generation.incrementAndGet()

    fun isCurrent(value: Long): Boolean = generation.get() == value
}
