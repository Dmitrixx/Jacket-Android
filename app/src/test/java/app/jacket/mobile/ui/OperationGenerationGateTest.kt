package app.jacket.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationGenerationGateTest {
    @Test
    fun newerOperationSupersedesPreviousGeneration() {
        val gate = OperationGenerationGate()
        val first = gate.begin()
        val second = gate.begin()

        assertTrue(second > first)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun disconnectInvalidatesAnInFlightOperation() {
        val gate = OperationGenerationGate()
        val inFlight = gate.begin()

        gate.invalidate()

        assertFalse(gate.isCurrent(inFlight))
    }
}
