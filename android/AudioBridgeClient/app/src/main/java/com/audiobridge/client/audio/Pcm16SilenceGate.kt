package com.audiobridge.client.audio

import kotlin.math.abs

/**
 * PCM16 单声道静音门（用于省流：静音连续一段时间后停止发送）。
 * 使用 avg(|sample|) 做能量估计，配合连续帧计数实现简单 hangover。
 * 支持动态修改阈值参数。
 */
class Pcm16SilenceGate(
    thresholdAvgAbs: Int = 120,
    minSilentFramesToSuppress: Int = 10,
) {
    /** 当前静音门阈值（可动态修改） */
    @Volatile
    var thresholdAvgAbs: Int = thresholdAvgAbs
        set(value) { field = value.coerceIn(0, 32767) }

    /** 最小静音帧数（可动态修改） */
    @Volatile
    var minSilentFramesToSuppress: Int = minSilentFramesToSuppress
        set(value) { field = value.coerceIn(1, 100) }

    private var silentRun = 0
    private var suppressing = false
    
    /** 最后一帧的平均绝对值（用于监控/调试） */
    @Volatile
    var lastAvgAbs: Int = 0
        private set

    val isSuppressing: Boolean get() = suppressing
    val silentRunFrames: Int get() = silentRun

    fun reset() {
        silentRun = 0
        suppressing = false
        lastAvgAbs = 0
    }

    /**
     * @return true=发送；false=丢弃以省流
     */
    fun shouldSend(pcm16LittleEndian: ByteArray): Boolean {
        val avgAbs = avgAbs(pcm16LittleEndian)
        lastAvgAbs = avgAbs
        val silent = avgAbs < thresholdAvgAbs

        if (silent) {
            silentRun++
            if (silentRun >= minSilentFramesToSuppress) {
                suppressing = true
            }
        } else {
            silentRun = 0
            suppressing = false
        }

        return !suppressing
    }

    private fun avgAbs(pcm: ByteArray): Int {
        if (pcm.size < 2) return 0
        if (pcm.size % 2 != 0) return 0

        var sum = 0L
        var i = 0
        while (i < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val v = ((hi shl 8) or lo).toShort().toInt()
            sum += abs(v)
            i += 2
        }

        return (sum / (pcm.size / 2)).toInt()
    }
}

