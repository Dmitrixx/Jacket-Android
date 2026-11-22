package app.jacket.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationGenerationGateTest {
    @Test
    fun latestGenerationWins() {
        val gate = OperationGenerationGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun invalidateMakesCurrentGenerationStale() {
        val gate = OperationGenerationGate()
        val current = gate.begin()

        gate.invalidate()

        assertFalse(gate.isCurrent(current))
    }
}
