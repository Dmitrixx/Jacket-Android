package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Callable

class CompletionOrderCollectorTest {
    @Test
    fun emitsFastTaskBeforeEarlierSlowTask() {
        val emitted = Collections.synchronizedList(mutableListOf<String>())
        val tasks = listOf(
            Callable {
                Thread.sleep(180)
                "slow"
            },
            Callable { "fast" }
        )

        val completed = CompletionOrderCollector.collect(
            tasks = tasks,
            timeoutMs = 2_000,
            maxConcurrency = 2
        ) { result ->
            emitted += result.getOrThrow()
        }

        assertEquals(2, completed)
        assertEquals(listOf("fast", "slow"), emitted)
    }

    @Test
    fun returnsCompletedPartialResultsWhenDeadlineExpires() {
        val emitted = Collections.synchronizedList(mutableListOf<String>())
        val tasks = listOf(
            Callable {
                Thread.sleep(1_000)
                "too-late"
            },
            Callable { "ready" }
        )

        val completed = CompletionOrderCollector.collect(
            tasks = tasks,
            timeoutMs = 120,
            maxConcurrency = 2
        ) { result ->
            emitted += result.getOrThrow()
        }

        assertEquals(1, completed)
        assertEquals(listOf("ready"), emitted)
    }
}
