package app.jacket.mobile.ui

/**
 * Prevents a cancelled search from clearing the loading state of its successor.
 * Access is synchronized because coroutine completion may run on a different dispatcher.
 */
internal class SourceSearchEpoch {
    private var current = 0L

    @Synchronized
    fun next(): Long {
        current += 1L
        return current
    }

    @Synchronized
    fun isCurrent(epoch: Long): Boolean = epoch == current
}
