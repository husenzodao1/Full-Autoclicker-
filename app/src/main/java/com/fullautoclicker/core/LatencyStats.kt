package com.fullautoclicker.core

/** Rolling latency statistics over the most recent [capacity] samples (milliseconds). */
class LatencyStats(private val capacity: Int = 64) {
    private val samples = LongArray(capacity)
    private var next = 0

    /** Number of samples currently in the window. */
    var size = 0
        private set

    /** Total samples ever recorded (not limited by the window). */
    var total = 0L
        private set

    var last = -1L
        private set

    fun record(ms: Long) {
        val value = if (ms < 0) 0 else ms
        samples[next] = value
        next = (next + 1) % capacity
        if (size < capacity) size++
        total++
        last = value
    }

    fun min(): Long = if (size == 0) -1 else (0 until size).minOf { samples[it] }

    fun max(): Long = if (size == 0) -1 else (0 until size).maxOf { samples[it] }

    fun average(): Double = if (size == 0) -1.0 else (0 until size).sumOf { samples[it] }.toDouble() / size

    /** Value below which [fraction] of the window falls (nearest-rank). */
    fun percentile(fraction: Double): Long {
        if (size == 0) return -1
        val sorted = samples.copyOf(size).also { it.sort() }
        val rank = kotlin.math.ceil(fraction * size).toInt().coerceIn(1, size)
        return sorted[rank - 1]
    }

    fun reset() {
        next = 0
        size = 0
        total = 0
        last = -1
    }
}
