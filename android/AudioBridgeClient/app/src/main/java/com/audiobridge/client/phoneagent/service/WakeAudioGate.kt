package com.audiobridge.client.phoneagent.service

import kotlin.math.abs

data class WakeAudioDecision(
    val shouldTranscribe: Boolean,
    val avgAbs: Int,
    val peakWindowAvgAbs: Int,
    val reason: String,
)

object WakeAudioGate {
    const val DEFAULT_THRESHOLD_AVG_ABS = 180
    const val DEFAULT_THRESHOLD_PEAK_WINDOW_AVG_ABS = 180
    const val DEFAULT_MIN_BYTES = 16_000
    const val DEFAULT_WINDOW_BYTES = 16_000

    fun evaluate(
        pcm16LittleEndian: ByteArray,
        thresholdAvgAbs: Int = DEFAULT_THRESHOLD_AVG_ABS,
        thresholdPeakWindowAvgAbs: Int = DEFAULT_THRESHOLD_PEAK_WINDOW_AVG_ABS,
        minBytes: Int = DEFAULT_MIN_BYTES,
        windowBytes: Int = DEFAULT_WINDOW_BYTES,
    ): WakeAudioDecision {
        if (pcm16LittleEndian.size < minBytes) {
            val avg = avgAbs(pcm16LittleEndian)
            return WakeAudioDecision(
                shouldTranscribe = false,
                avgAbs = avg,
                peakWindowAvgAbs = avg,
                reason = "too_short",
            )
        }
        val avg = avgAbs(pcm16LittleEndian)
        val peak = peakWindowAvgAbs(pcm16LittleEndian, windowBytes)
        if (avg < thresholdAvgAbs && peak < thresholdPeakWindowAvgAbs) {
            return WakeAudioDecision(
                shouldTranscribe = false,
                avgAbs = avg,
                peakWindowAvgAbs = peak,
                reason = "silent",
            )
        }
        return WakeAudioDecision(
            shouldTranscribe = true,
            avgAbs = avg,
            peakWindowAvgAbs = peak,
            reason = "speech",
        )
    }

    fun avgAbs(pcm16LittleEndian: ByteArray): Int {
        if (pcm16LittleEndian.size < 2 || pcm16LittleEndian.size % 2 != 0) return 0
        var sum = 0L
        var i = 0
        while (i < pcm16LittleEndian.size) {
            val lo = pcm16LittleEndian[i].toInt() and 0xFF
            val hi = pcm16LittleEndian[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sum += abs(sample)
            i += 2
        }
        return (sum / (pcm16LittleEndian.size / 2)).toInt()
    }

    fun peakWindowAvgAbs(
        pcm16LittleEndian: ByteArray,
        windowBytes: Int = DEFAULT_WINDOW_BYTES,
    ): Int {
        if (pcm16LittleEndian.size < 2) return 0
        val evenWindowBytes = windowBytes.coerceAtLeast(2).let { it - (it % 2) }
        val actualWindowBytes = minOf(evenWindowBytes, pcm16LittleEndian.size - (pcm16LittleEndian.size % 2))
        if (actualWindowBytes <= 0) return 0
        val stepBytes = (actualWindowBytes / 2).coerceAtLeast(2).let { it - (it % 2) }
        var peak = 0
        var offset = 0
        while (offset + actualWindowBytes <= pcm16LittleEndian.size) {
            val windowAvg = avgAbs(pcm16LittleEndian.copyOfRange(offset, offset + actualWindowBytes))
            if (windowAvg > peak) peak = windowAvg
            offset += stepBytes.coerceAtLeast(2)
        }
        return peak
    }
}
