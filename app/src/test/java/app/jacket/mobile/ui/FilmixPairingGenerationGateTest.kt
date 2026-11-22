package app.jacket.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmixPairingGenerationGateTest {
    @Test
    fun latestGenerationWins() {
        val gate = FilmixPairingGenerationGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun invalidateMakesCurrentGenerationStale() {
        val gate = FilmixPairingGenerationGate()
        val current = gate.begin()

        gate.invalidate()

        assertFalse(gate.isCurrent(current))
    }
}
