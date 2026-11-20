package app.jacket.mobile.data

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Consumes concurrent work as soon as each task finishes and cancels unfinished
 * tasks after a shared deadline. One slow request cannot block already-completed work.
 */
internal object CompletionOrderCollector {
    fun <T> collect(
        tasks: List<Callable<T>>,
        timeoutMs: Long,
        maxConcurrency: Int,
        onResult: (Result<T>) -> Unit
    ): Int {
        if (tasks.isEmpty()) return 0
        require(timeoutMs > 0L) { "Timeout must be positive" }
        require(maxConcurrency > 0) { "Concurrency must be positive" }

        val executor = Executors.newFixedThreadPool(minOf(maxConcurrency, tasks.size))
        val completion = ExecutorCompletionService<T>(executor)
        val futures = tasks.map(completion::submit)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var completed = 0
        try {
            while (completed < tasks.size) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) break
                val future = completion.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: break
                val result = try {
                    Result.success(future.get())
                } catch (error: Exception) {
                    Result.failure(error.cause ?: error)
                }
                completed++
                onResult(result)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Ожидание параллельных запросов прервано", interrupted)
        } finally {
            futures.filterNot { it.isDone }.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
        return completed
    }
}
