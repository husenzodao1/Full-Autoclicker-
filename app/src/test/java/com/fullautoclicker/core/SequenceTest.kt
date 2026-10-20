package com.fullautoclicker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceTest {

    private val portrait = DisplayGeometry(1080, 2340, 0)

    private fun tap(x: Int, y: Int, gap: Long, hold: Long = 20) = TapStep(Point(x, y), hold, gap)

    private fun path(durationMs: Long, gap: Long, vararg xy: Int) =
        PathStep(xy.toList().chunked(2).map { Point(it[0], it[1]) }, durationMs, gap)

    /** The user's example: X →(40 ms)→ X → B (0.5 s) → B (0.2 s) → X. */
    private val example = listOf(
        tap(100, 200, 40),
        tap(300, 400, 40),
        path(500, 40, 100, 1000, 900, 1000),
        path(200, 40, 900, 1200, 100, 1200),
        tap(540, 1800, 40),
    )

    @Test
    fun timelinePlaysStepsInOrderWithTheirGapsAndDurations() {
        val slots = SequenceRules.timeline(example)
        assertEquals(listOf(0L to 20L, 60L to 20L, 120L to 500L, 660L to 200L, 900L to 20L), slots)
        assertEquals(920L, SequenceRules.totalDurationMs(example))
    }

    @Test
    fun eachTapHasItsOwnPressLengthUpToFiveSeconds() {
        val steps = listOf(tap(10, 10, gap = 40, hold = 20), tap(20, 20, gap = 40, hold = 3000))
        assertEquals(listOf(0L to 20L, 60L to 3000L), SequenceRules.timeline(steps))
        val n = SequenceRules.normalize(listOf(tap(1, 1, 40, hold = 0), tap(2, 2, 40, hold = 99_999)), 100, 100)
        assertEquals(SequenceRules.MIN_HOLD_MS, (n[0] as TapStep).holdMs)
        assertEquals(SequenceRules.MAX_HOLD_MS, (n[1] as TapStep).holdMs)
    }

    @Test
    fun normalizeClampsCoordinatesAndTimings() {
        val raw = listOf(
            tap(-5, 5000, 0),
            path(5, 99_999, -10, -10, 2000, 100),
        )
        val n = SequenceRules.normalize(raw, 1080, 2340)
        assertEquals(TapStep(Point(0, 2339), 20, SequenceRules.MIN_GAP_MS), n[0])
        val p = n[1] as PathStep
        assertEquals(Point(0, 0), p.points.first())
        assertEquals(Point(1079, 100), p.points.last())
        assertEquals(SequenceRules.MIN_PATH_MS, p.durationMs)
        assertEquals(SequenceRules.MAX_GAP_MS, p.delayAfterMs)
    }

    @Test
    fun normalizeKeepsAtMostTenSteps() {
        val many = List(15) { tap(it, it, 50) }
        assertEquals(SequenceRules.MAX_STEPS, SequenceRules.normalize(many, 1080, 2340).size)
    }

    @Test
    fun simplifyThinsLongTracesButKeepsTheEnds() {
        val trace = List(5000) { Point(it / 5, 100) }
        val s = SequenceRules.simplify(trace)
        assertTrue(s.size <= SequenceRules.MAX_PATH_POINTS)
        assertEquals(trace.first(), s.first())
        assertEquals(trace.last(), s.last())
    }

    @Test
    fun tooLongSequenceIsDetected() {
        val long = List(10) { path(10_000, 10_000, 0, 0, 100, 100) }
        assertTrue(SequenceRules.isTooLong(long))
        assertFalse(SequenceRules.isTooLong(example))
    }

    @Test
    fun codecRoundTrips() {
        val seq = TapSequence(example, portrait)
        assertEquals(seq, SequenceCodec.decode(SequenceCodec.encode(seq)))
    }

    @Test
    fun codecRejectsGarbage() {
        assertNull(SequenceCodec.decode(null))
        assertNull(SequenceCodec.decode(""))
        assertNull(SequenceCodec.decode("2|a,b,c|T,1,2,3,4"))
        assertNull(SequenceCodec.decode("9|1,2,0|T,1,2,3,4"))
        assertNull(SequenceCodec.decode("2|1,2,0|X,1,2,3,4"))
        assertNull(SequenceCodec.decode("2|1,2,0|T,1,2,3"))
    }

    // ---- engine with sequences

    private class Recorder : TapDispatcher {
        val runs = mutableListOf<Pair<Long, TapSequence>>()
        override fun dispatch(id: Long, sequence: TapSequence): DispatchResult {
            runs += id to sequence
            return DispatchResult.ACCEPTED
        }
    }

    private var now = 1_000L
    private val recorder = Recorder()
    private val engine = ClickEngine(recorder) { now }
    private var downTime = 10_000L

    private fun press() {
        val t = downTime++
        engine.onVolumeUpKey(true, 0, t, t, portrait)
        engine.onVolumeUpKey(false, 0, t, t + 80, portrait)
    }

    private fun armExample() {
        engine.beginSelection()
        assertTrue(engine.confirmSequence(TapSequence(example, portrait)))
    }

    @Test
    fun onePressRunsTheWholeSequenceOnce() {
        armExample()
        press()
        assertEquals(1, recorder.runs.size)
        assertEquals(example, recorder.runs.single().second.steps)
    }

    @Test
    fun emptySequenceCannotBeArmed() {
        engine.beginSelection()
        assertFalse(engine.confirmSequence(TapSequence(emptyList(), portrait)))
        assertEquals(Mode.SELECTING, engine.mode)
    }

    @Test
    fun longRunIsNotWrittenOffWhileItIsStillPlaying() {
        armExample()
        press()
        val first = recorder.runs.single().first
        now += 920 + ClickEngine.STUCK_TAP_MS - 10 // still within run length + margin
        press()
        assertEquals("second press waits behind the running sequence", 1, recorder.runs.size)
        assertEquals(0, engine.tapsLost)
        engine.onTapFinished(first, TapOutcome.COMPLETED)
        assertEquals(2, recorder.runs.size)
    }

    @Test
    fun retimeChangesTimingsButNotPlaces() {
        armExample()
        val faster = example.map {
            when (it) {
                is TapStep -> it.copy(delayAfterMs = 30, holdMs = 1500)
                is PathStep -> it.copy(durationMs = 100)
            }
        }
        assertTrue(engine.retime(faster))
        assertEquals(faster, engine.sequence!!.steps)

        val moved = listOf(tap(1, 1, 30, hold = 1500)) + faster.drop(1)
        assertFalse(engine.retime(moved))
        assertEquals(faster, engine.sequence!!.steps)
    }
}
