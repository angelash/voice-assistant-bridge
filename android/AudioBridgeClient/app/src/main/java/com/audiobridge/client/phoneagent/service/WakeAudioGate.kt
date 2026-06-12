package com.audiobridge.client.phoneagent.service

import kotlin.math.abs

data class WakeAudioDecision(
    val shouldTranscribe: Boolean,
    val avgAbs: Int,
    val reason: String,
)

object WakeAudioGate {
    const val DEFAULT_THRESHOLD_AVG_ABS = 180
    const val DEFAULT_MIN_BYTES = 16_000

    fun evaluate(
        pcm16LittleEndian: ByteArray,
        thresholdAvgAbs: Int = DEFAULT_THRESHOLD_AVG_ABS,
        minBytes: Int = DEFAULT_MIN_BYTES,
    ): WakeAudioDecision {
        if (pcm16LittleEndian.size < minBytes) {
            return WakeAudioDecision(
                shouldTranscribe = false,
                avgAbs = avgAbs(pcm16LittleEndian),
                reason = "too_short",
            )
        }
        val avg = avgAbs(pcm16LittleEndian)
        if (avg < thresholdAvgAbs) {
            return WakeAudioDecision(
                shouldTranscribe = false,
                avgAbs = avg,
                reason = "silent",
            )
        }
        return WakeAudioDecision(
            shouldTranscribe = true,
            avgAbs = avg,
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
}
