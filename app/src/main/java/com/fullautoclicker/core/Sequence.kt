package com.fullautoclicker.core

import kotlin.math.hypot

/** Limits and timing math for tap sequences. */
object SequenceRules {
    const val MAX_STEPS = 10

    /** Steps closer than this would be merged by Android into one multi-finger touch. */
    const val MIN_GAP_MS = 10L
    const val MIN_HOLD_MS = 1L
    const val MAX_HOLD_MS = 5_000L
    const val MAX_GAP_MS = 10_000L
    const val MIN_PATH_MS = 20L
    const val MAX_PATH_MS = 10_000L

    /** Android rejects a single gesture longer than 60 s. */
    const val MAX_TOTAL_MS = 59_000L
    const val MAX_PATH_POINTS = 200

    /** A drawing shorter than this (in pixels) is treated as an accidental touch, not a path. */
    const val MIN_PATH_LENGTH_PX = 20.0

    const val DEFAULT_HOLD_MS = 20L
    const val DEFAULT_GAP_MS = 50L
    const val DEFAULT_PATH_MS = 300L

    fun clampGap(ms: Long) = ms.coerceIn(MIN_GAP_MS, MAX_GAP_MS)

    fun clampHold(ms: Long) = ms.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)

    fun clampPath(ms: Long) = ms.coerceIn(MIN_PATH_MS, MAX_PATH_MS)

    /** Start time (from gesture start) and duration of every step. */
    fun timeline(steps: List<Step>): List<Pair<Long, Long>> {
        var t = 0L
        return steps.map { step ->
            val duration = durationOf(step)
            val slot = t to duration
            t += duration + step.delayAfterMs
            slot
        }
    }

    /** Time from the first touch to the last release of one run. */
    fun totalDurationMs(steps: List<Step>): Long {
        val last = timeline(steps).lastOrNull() ?: return 0L
        return last.first + last.second
    }

    fun durationOf(step: Step): Long = when (step) {
        is TapStep -> step.holdMs
        is PathStep -> step.durationMs
    }

    fun isTooLong(steps: List<Step>) = totalDurationMs(steps) > MAX_TOTAL_MS

    fun pathLength(points: List<Point>): Double =
        points.zipWithNext { a, b -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }.sum()

    /**
     * Thins a finger trace: drops points closer than [minStepPx] to the last kept one, then keeps
     * at most [MAX_PATH_POINTS] evenly spaced points. The last point is always kept.
     */
    fun simplify(points: List<Point>, minStepPx: Double = 4.0): List<Point> {
        if (points.size <= 2) return points
        val kept = ArrayList<Point>()
        for (p in points) {
            val last = kept.lastOrNull()
            if (last == null || hypot((p.x - last.x).toDouble(), (p.y - last.y).toDouble()) >= minStepPx) kept += p
        }
        if (kept.last() != points.last()) kept += points.last()
        if (kept.size <= MAX_PATH_POINTS) return kept
        val stride = (kept.size - 1).toDouble() / (MAX_PATH_POINTS - 1)
        return List(MAX_PATH_POINTS) { i -> kept[(i * stride).toInt().coerceAtMost(kept.size - 1)] }
    }

    /** Keeps every coordinate on screen (Android rejects negative path coordinates) and every timing in range. */
    fun normalize(steps: List<Step>, width: Int, height: Int): List<Step> {
        fun Point.clamp() = Point(x.coerceIn(0, (width - 1).coerceAtLeast(0)), y.coerceIn(0, (height - 1).coerceAtLeast(0)))
        return steps.take(MAX_STEPS).mapNotNull { step ->
            when (step) {
                is TapStep -> TapStep(step.at.clamp(), clampHold(step.holdMs), clampGap(step.delayAfterMs))
                is PathStep -> {
                    val pts = simplify(step.points.map { it.clamp() })
                    if (pts.size < 2) null else PathStep(pts, clampPath(step.durationMs), clampGap(step.delayAfterMs))
                }
            }
        }
    }

    /** True when [b] has the same steps as [a] at the same places, so only timings differ. */
    fun sameShape(a: List<Step>, b: List<Step>): Boolean = a.size == b.size && a.zip(b).all { (x, y) ->
        when {
            x is TapStep && y is TapStep -> x.at == y.at
            x is PathStep && y is PathStep -> x.points == y.points
            else -> false
        }
    }
}

/**
 * Compact text form for storing the last sequence:
 * `2|w,h,rot|T,x,y,hold,gap;P,dur,gap,x:y x:y ...`
 */
object SequenceCodec {
    fun encode(sequence: TapSequence): String {
        val g = sequence.geometry
        val steps = sequence.steps.joinToString(";") { step ->
            when (step) {
                is TapStep -> "T,${step.at.x},${step.at.y},${step.holdMs},${step.delayAfterMs}"
                is PathStep -> "P,${step.durationMs},${step.delayAfterMs}," +
                    step.points.joinToString(" ") { "${it.x}:${it.y}" }
            }
        }
        return "2|${g.width},${g.height},${g.rotation}|$steps"
    }

    /** Returns null for anything malformed, so a bad value can never crash the app. */
    fun decode(text: String?): TapSequence? = try {
        val parts = text!!.split("|")
        require(parts.size == 3 && parts[0] == "2")
        val (w, h, r) = parts[1].split(",").map { it.toInt() }
        val steps = if (parts[2].isEmpty()) emptyList() else parts[2].split(";").map { s ->
            when (s.substringBefore(',')) {
                "T" -> s.split(",").let { f -> TapStep(Point(f[1].toInt(), f[2].toInt()), f[3].toLong(), f[4].toLong()) }
                "P" -> s.split(",", limit = 4).let { f ->
                    PathStep(
                        f[3].split(" ").map { p -> p.split(":").let { Point(it[0].toInt(), it[1].toInt()) } },
                        f[1].toLong(),
                        f[2].toLong(),
                    )
                }
                else -> error("bad step")
            }
        }
        TapSequence(steps, DisplayGeometry(w, h, r))
    } catch (e: Exception) {
        null
    }
}

