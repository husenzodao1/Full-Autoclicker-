package com.fullautoclicker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClickEngineTest {

    private class FakeDispatcher : TapDispatcher {
        val taps = mutableListOf<Triple<Long, Int, Int>>()
        var result = DispatchResult.ACCEPTED
        var throwOnDispatch = false

        override fun dispatchTap(id: Long, x: Int, y: Int): DispatchResult {
            if (throwOnDispatch) throw IllegalStateException("boom")
            taps += Triple(id, x, y)
            return result
        }
    }

    private val portrait = DisplayGeometry(1080, 2340, 0)
    private val landscape = DisplayGeometry(2340, 1080, 1)

    private var now = 10_000L
    private lateinit var dispatcher: FakeDispatcher
    private lateinit var engine: ClickEngine
    private var nextDownTime = 50_000L

    @Before
    fun setUp() {
        dispatcher = FakeDispatcher()
        engine = ClickEngine(dispatcher) { now }
    }

    private fun arm(x: Int = 540, y: Int = 1170, geometry: DisplayGeometry = portrait) {
        engine.beginSelection()
        assertTrue(engine.confirmTarget(Target(x, y, geometry)))
    }

    /** Sends a full press: down, [repeats] auto-repeats, up. Returns the verdicts. */
    private fun press(repeats: Int = 0, geometry: DisplayGeometry = portrait): List<KeyVerdict> {
        val downTime = nextDownTime++
        val verdicts = mutableListOf<KeyVerdict>()
        verdicts += engine.onVolumeUpKey(true, 0, downTime, downTime, geometry)
        for (i in 1..repeats) verdicts += engine.onVolumeUpKey(true, i, downTime, downTime + 400 + i * 50L, geometry)
        verdicts += engine.onVolumeUpKey(false, 0, downTime, downTime + 100, geometry)
        return verdicts
    }

    private fun finishLastTap(outcome: TapOutcome = TapOutcome.COMPLETED) {
        engine.onTapFinished(dispatcher.taps.last().first, outcome)
    }

    @Test
    fun newEngineStartsIdleWithNothingArmed() {
        assertEquals(Mode.IDLE, engine.mode)
        assertNull(engine.target)
        assertEquals(listOf(KeyVerdict.PASS, KeyVerdict.PASS), press())
        assertTrue(dispatcher.taps.isEmpty())
    }

    @Test
    fun selectingNeverTapsAndLeavesVolumeAlone() {
        engine.beginSelection()
        assertEquals(listOf(KeyVerdict.PASS, KeyVerdict.PASS), press())
        assertEquals(listOf(KeyVerdict.PASS, KeyVerdict.PASS, KeyVerdict.PASS), press(repeats = 1))
        assertTrue(dispatcher.taps.isEmpty())
    }

    @Test
    fun onePressIsExactlyOneTapAtTheTarget() {
        arm(321, 654)
        assertEquals(listOf(KeyVerdict.CONSUME, KeyVerdict.CONSUME), press())
        assertEquals(1, dispatcher.taps.size)
        assertEquals(321, dispatcher.taps[0].second)
        assertEquals(654, dispatcher.taps[0].third)
    }

    @Test
    fun holdingTheKeyTapsOnceAndSwallowsRepeats() {
        arm()
        val verdicts = press(repeats = 25)
        assertTrue(verdicts.all { it == KeyVerdict.CONSUME })
        assertEquals(1, dispatcher.taps.size)
        assertEquals(25, engine.repeatsSwallowed)
    }

    @Test
    fun rapidPressesEachTapOnce() {
        arm()
        repeat(30) {
            press()
            finishLastTap()
        }
        assertEquals(30, dispatcher.taps.size)
        assertEquals(30, engine.tapsCompleted)
        assertEquals(0, engine.tapsDropped)
    }

    @Test
    fun duplicateDeliveryOfOnePressDoesNotTapTwice() {
        arm()
        val downTime = 777L
        assertEquals(KeyVerdict.CONSUME, engine.onVolumeUpKey(true, 0, downTime, downTime, portrait))
        assertEquals(KeyVerdict.CONSUME, engine.onVolumeUpKey(true, 0, downTime, downTime, portrait))
        assertEquals(KeyVerdict.CONSUME, engine.onVolumeUpKey(false, 0, downTime, downTime + 80, portrait))
        assertEquals(1, dispatcher.taps.size)
    }

    @Test
    fun queueHoldsAtMostOneTapBehindTheOneInFlight() {
        arm()
        press() // dispatched, in flight
        press() // queued
        press() // dropped
        press() // dropped
        assertEquals(1, dispatcher.taps.size)
        assertEquals(2, engine.tapsDropped)

        finishLastTap()
        assertEquals("queued tap goes out when the first finishes", 2, dispatcher.taps.size)
        finishLastTap()
        assertEquals(2, dispatcher.taps.size)
        assertFalse(engine.isTapInFlight)
    }

    @Test
    fun stopDiscardsQueuedTapAndBlocksNewOnes() {
        arm()
        press()
        press() // queued behind the in-flight tap
        engine.stop("test")
        assertEquals(Mode.IDLE, engine.mode)

        finishLastTap()
        assertEquals("queued tap must not be dispatched after STOP", 1, dispatcher.taps.size)
        assertEquals(listOf(KeyVerdict.PASS, KeyVerdict.PASS), press())
        assertEquals(1, dispatcher.taps.size)
        assertEquals("test", engine.lastStopReason)
    }

    @Test
    fun stopWhileKeyIsHeldStillConsumesThatPressesKeyUp() {
        arm()
        val downTime = 900L
        assertEquals(KeyVerdict.CONSUME, engine.onVolumeUpKey(true, 0, downTime, downTime, portrait))
        engine.stop("test")
        assertEquals(KeyVerdict.CONSUME, engine.onVolumeUpKey(true, 1, downTime, downTime + 500, portrait))
        assertEquals(KeyVerdict.CONSUME, engine.onVolumeUpKey(false, 0, downTime, downTime + 600, portrait))
        assertEquals(listOf(KeyVerdict.PASS, KeyVerdict.PASS), press())
        assertEquals(1, dispatcher.taps.size)
    }

    @Test
    fun pressThatStartedBeforeArmingIsLeftToAndroid() {
        val downTime = 1234L
        assertEquals(KeyVerdict.PASS, engine.onVolumeUpKey(true, 0, downTime, downTime, portrait))
        arm()
        assertEquals(KeyVerdict.PASS, engine.onVolumeUpKey(true, 1, downTime, downTime + 500, portrait))
        assertEquals(KeyVerdict.PASS, engine.onVolumeUpKey(false, 0, downTime, downTime + 600, portrait))
        assertTrue(dispatcher.taps.isEmpty())
    }

    @Test
    fun rotationPausesTappingUntilTheGeometryMatchesAgain() {
        arm(geometry = portrait)
        assertEquals(listOf(KeyVerdict.PASS, KeyVerdict.PASS), press(geometry = landscape))
        assertTrue(engine.isPaused(landscape))
        assertTrue(dispatcher.taps.isEmpty())
        assertEquals(1, engine.pressesWhilePaused)

        assertFalse(engine.isPaused(portrait))
        assertEquals(listOf(KeyVerdict.CONSUME, KeyVerdict.CONSUME), press(geometry = portrait))
        assertEquals(1, dispatcher.taps.size)
    }

    @Test
    fun rejectedDispatchIsCountedAndDoesNotBlockLaterTaps() {
        arm()
        dispatcher.result = DispatchResult.REJECTED
        press()
        assertEquals(1, engine.tapsFailed)
        assertFalse(engine.isTapInFlight)

        dispatcher.result = DispatchResult.ACCEPTED
        press()
        assertTrue(engine.isTapInFlight)
        assertEquals(2, dispatcher.taps.size)
    }

    @Test
    fun dispatcherExceptionIsTreatedAsRejection() {
        arm()
        dispatcher.throwOnDispatch = true
        assertEquals(listOf(KeyVerdict.CONSUME, KeyVerdict.CONSUME), press())
        assertEquals(1, engine.tapsFailed)
        assertFalse(engine.isTapInFlight)
    }

    @Test
    fun lostCallbackDoesNotBlockClickingForever() {
        arm()
        press()
        val lostId = dispatcher.taps.last().first
        now += ClickEngine.STUCK_TAP_MS + 1
        press()
        assertEquals(2, dispatcher.taps.size)
        assertEquals(1, engine.tapsLost)

        // Late callback for the written-off tap.
        assertNull(engine.onTapFinished(lostId, TapOutcome.COMPLETED))
        assertTrue("stale callback must not clear the current tap", engine.isTapInFlight)
        assertEquals(0, engine.tapsCompleted)
    }

    @Test
    fun reArmingDoesNotWaitOnATapFromThePreviousSession() {
        arm()
        press()
        val orphanId = dispatcher.taps.last().first
        engine.stop("test") // its callback never arrives
        arm()
        press()
        assertEquals("first press after READY taps immediately", 2, dispatcher.taps.size)
        assertNull(engine.onTapFinished(orphanId, TapOutcome.COMPLETED))
        assertTrue(engine.isTapInFlight)
    }

    @Test
    fun cancelledTapIsCountedAndQueueContinues() {
        arm()
        press()
        press()
        finishLastTap(TapOutcome.CANCELLED)
        assertEquals(1, engine.tapsCancelled)
        assertEquals(2, dispatcher.taps.size)
    }

    @Test
    fun readyOutsideSelectionIsIgnored() {
        assertFalse(engine.confirmTarget(Target(1, 2, portrait)))
        assertEquals(Mode.IDLE, engine.mode)
        assertNull(engine.target)
    }

    @Test
    fun cancelSelectionGoesIdleWithoutArming() {
        engine.beginSelection()
        engine.cancelSelection()
        assertEquals(Mode.IDLE, engine.mode)
        assertEquals(listOf(KeyVerdict.PASS, KeyVerdict.PASS), press())
    }

    @Test
    fun reselectingWhileArmedStopsTappingUntilReady() {
        arm(100, 200)
        press()
        finishLastTap()
        engine.beginSelection()
        assertEquals(listOf(KeyVerdict.PASS, KeyVerdict.PASS), press())
        assertTrue(engine.confirmTarget(Target(300, 400, portrait)))
        press()
        assertEquals(2, dispatcher.taps.size)
        assertEquals(300, dispatcher.taps.last().second)
    }

    @Test
    fun latencyIsMeasuredFromThePhysicalKeyEvent() {
        arm()
        val downTime = 20_000L
        now = downTime + 3
        engine.onVolumeUpKey(true, 0, downTime, downTime, portrait)
        assertEquals(3, engine.dispatchLatency.last)
        now = downTime + 27
        finishLastTap()
        assertEquals(27, engine.completeLatency.last)
        assertEquals(engine.lastDispatched?.keyEventTime, downTime)
    }

    @Test
    fun volumeUpKeyUpWithoutOwnedDownPasses() {
        arm()
        assertEquals(KeyVerdict.PASS, engine.onVolumeUpKey(false, 0, 42L, 50L, portrait))
    }
}
