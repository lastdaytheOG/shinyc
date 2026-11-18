package com.amar.vault.indexing

import android.os.PowerManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class HeatPacerTest {

    private class Clock {
        var at = 1_000_000L
    }

    private val emulatorLines = mapOf(
        PowerManager.THERMAL_STATUS_NONE to Float.NaN,
        PowerManager.THERMAL_STATUS_LIGHT to 0.9333f,
        PowerManager.THERMAL_STATUS_MODERATE to 0.9667f,
        PowerManager.THERMAL_STATUS_SEVERE to 1.0f,
    )

    private class ScriptedGauge(
        private val clock: Clock,
        private val headroomAt: (Long) -> Float,
        private val statusAt: (Long) -> Int = { PowerManager.THERMAL_STATUS_NONE },
        private val lines: Map<Int, Float> = emptyMap(),
    ) : HeatPacer.Gauge {
        val asked = mutableListOf<Long>()
        override fun headroom(forecastSeconds: Int): Float {
            asked += clock.at
            return headroomAt(clock.at)
        }
        override fun status(): Int = statusAt(clock.at)
        override fun lines(): Map<Int, Float> = lines
    }

    private fun pacer(gauge: HeatPacer.Gauge, clock: Clock, maxPauseMs: Long = 60_000L) =
        HeatPacer(gauge, maxPauseMs, now = { clock.at }, sleep = { clock.at += it })

    @Test
    fun aCoolPhoneIsNotHeldUp() = runBlocking {
        val clock = Clock()

        assertEquals(0L, pacer(ScriptedGauge(clock, { 0.4f }, lines = emulatorLines), clock).awaitCool())
    }

    @Test
    fun theEmulatorsSteadyReadingIsNotTakenForHeat() = runBlocking {
        val clock = Clock()

        assertEquals(0L, pacer(ScriptedGauge(clock, { 0.90333337f }, lines = emulatorLines), clock).awaitCool())
    }

    @Test
    fun aForecastPastThePhonesModerateLineWaitsUntilUnderItsLightLine() = runBlocking {
        val clock = Clock()
        val start = clock.at
        val gauge = ScriptedGauge(clock, {
            when {
                it == start -> 0.98f
                it - start < 15_000L -> 0.95f
                else -> 0.92f
            }
        }, lines = emulatorLines)

        assertEquals(15_000L, pacer(gauge, clock).awaitCool())
    }

    @Test
    fun withoutThePhonesLinesOnlyAForecastOfSevereHoldsUp() = runBlocking {
        val clock = Clock()

        assertEquals(0L, pacer(ScriptedGauge(clock, { 0.98f }), clock).awaitCool())
        val start = clock.at + 10_000L
        clock.at = start
        val gauge = ScriptedGauge(clock, { if (it == start) 1.02f else if (it - start < 10_000L) 0.93f else 0.85f })
        assertEquals(10_000L, pacer(gauge, clock).awaitCool())
    }

    @Test
    fun aModerateStatusHoldsUpUntilItIsLightAgain() = runBlocking {
        val clock = Clock()
        val coolFrom = clock.at + 7_000L
        val gauge = ScriptedGauge(
            clock,
            { Float.NaN },
            { if (it >= coolFrom) PowerManager.THERMAL_STATUS_LIGHT else PowerManager.THERMAL_STATUS_MODERATE },
        )

        assertEquals(10_000L, pacer(gauge, clock).awaitCool())
    }

    @Test
    fun aPauseEndsAtItsLongestEvenWhenThePhoneStaysWarm() = runBlocking {
        val clock = Clock()
        val gauge = ScriptedGauge(clock, { 0.99f }, lines = emulatorLines)

        assertEquals(12_000L, pacer(gauge, clock, maxPauseMs = 12_000L).awaitCool())
    }

    @Test
    fun aStoppedRunDoesNotWaitForTheHeat() = runBlocking {
        val clock = Clock()
        val gauge = ScriptedGauge(clock, { 0.99f }, lines = emulatorLines)

        assertEquals(0L, pacer(gauge, clock).awaitCool { false })
    }

    @Test
    fun theForecastIsNotAskedForMoreThanOnceInTwoSeconds() = runBlocking {
        val clock = Clock()
        val gauge = ScriptedGauge(clock, { 0.4f })
        val pacer = pacer(gauge, clock)

        repeat(5) {
            pacer.awaitCool()
            clock.at += 500
        }

        assertEquals(2, gauge.asked.size)
    }

    @Test
    fun aPhoneThatCannotTellItsHeatIsGivenAShortRestEachTime() = runBlocking {
        val clock = Clock()

        val paused = pacer(ScriptedGauge(clock, { Float.NaN }, { -1 }), clock).awaitCool()

        assertEquals(HeatPacer.BLIND_PAUSE_MS, paused)
        assertTrue(paused * 8 <= 2_000L)
    }
}
