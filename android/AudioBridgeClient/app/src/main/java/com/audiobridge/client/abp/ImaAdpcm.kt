package com.audiobridge.client.abp

import kotlin.math.abs

/**
 * IMA ADPCM（4-bit）单声道 PCM16 编解码。
 * - 每个帧独立：payload 头部包含 predictor(int16) + index(byte) + reserved(byte)
 * - 紧随其后是 4-bit nibble 数据：每个样本（除第一个）占 4bit
 * - nibble 打包：先低 4bit、后高 4bit（与 Windows 端一致）
 */
object ImaAdpcm {
    const val BLOCK_HEADER_SIZE = 4

    private val stepTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
        34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
        724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
        3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )

    private val indexTable = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8,
    )

    fun encodePcm16Mono(pcmLittleEndian: ByteArray, expectedSamples: Int): ByteArray {
        require(expectedSamples > 1) { "expectedSamples must be > 1" }
        require(pcmLittleEndian.size == expectedSamples * 2) {
            "pcm length mismatch: ${pcmLittleEndian.size} != ${expectedSamples * 2}"
        }

        val nibbleCount = expectedSamples - 1
        val dataBytes = (nibbleCount + 1) / 2
        val out = ByteArray(BLOCK_HEADER_SIZE + dataBytes)

        val predictor0 = readS16LE(pcmLittleEndian, 0)
        val index0 = estimateStartIndex(pcmLittleEndian, predictor0)

        writeS16LE(out, 0, predictor0)
        out[2] = index0.toByte()
        out[3] = 0

        val state = intArrayOf(predictor0.toInt(), index0) // [pred, idx]

        var outPos = BLOCK_HEADER_SIZE
        var pack = 0
        var packLow = true

        for (s in 1 until expectedSamples) {
            val sample = readS16LE(pcmLittleEndian, s * 2).toInt()
            val nibble = encodeNibble(sample, state) and 0x0F

            if (packLow) {
                pack = nibble
                packLow = false
            } else {
                pack = pack or (nibble shl 4)
                out[outPos++] = pack.toByte()
                pack = 0
                packLow = true
            }
        }

        if (!packLow) {
            out[outPos] = pack.toByte()
        }

        return out
    }

    fun decodeToPcm16Mono(adpcmPayload: ByteArray, expectedSamples: Int): ByteArray {
        require(expectedSamples > 1) { "expectedSamples must be > 1" }
        require(adpcmPayload.size >= BLOCK_HEADER_SIZE) { "adpcm too short: ${adpcmPayload.size}" }

        val nibbleCount = expectedSamples - 1
        val expectedDataBytes = (nibbleCount + 1) / 2
        val expectedTotal = BLOCK_HEADER_SIZE + expectedDataBytes
        require(adpcmPayload.size == expectedTotal) {
            "adpcm length mismatch: ${adpcmPayload.size} != $expectedTotal"
        }

        val state = intArrayOf(
            readS16LE(adpcmPayload, 0).toInt(),
            (adpcmPayload[2].toInt() and 0xFF).coerceIn(0, 88),
        ) // [pred, idx]

        val pcm = ByteArray(expectedSamples * 2)
        writeS16LE(pcm, 0, state[0].toShort())

        var inPos = BLOCK_HEADER_SIZE
        var useLow = true
        var cur = adpcmPayload[inPos].toInt() and 0xFF

        for (s in 1 until expectedSamples) {
            val nibble = if (useLow) (cur and 0x0F) else ((cur ushr 4) and 0x0F)

            // 用完高 nibble 后再移动到下一个字节（与打包顺序对齐）
            if (!useLow) {
                inPos++
                if (inPos < adpcmPayload.size) {
                    cur = adpcmPayload[inPos].toInt() and 0xFF
                }
            }

            useLow = !useLow

            decodeNibble(nibble, state)
            writeS16LE(pcm, s * 2, state[0].toShort())
        }

        return pcm
    }

    private fun estimateStartIndex(pcm: ByteArray, predictor: Short): Int {
        if (pcm.size < 4) return 0
        val next = readS16LE(pcm, 2)
        val diff = abs(next.toInt() - predictor.toInt())
        var idx = 0
        while (idx < stepTable.size - 1 && stepTable[idx] < diff) {
            idx++
        }
        return idx
    }

    private fun encodeNibble(sample: Int, state: IntArray): Int {
        var pred = state[0]
        var idx = state[1]

        val step = stepTable[idx]
        var diff = sample - pred
        var sign = 0
        if (diff < 0) {
            sign = 8
            diff = -diff
        }

        var delta = 0
        var vpdiff = step ushr 3

        if (diff >= step) {
            delta = delta or 4
            diff -= step
            vpdiff += step
        }
        if (diff >= (step ushr 1)) {
            delta = delta or 2
            diff -= (step ushr 1)
            vpdiff += (step ushr 1)
        }
        if (diff >= (step ushr 2)) {
            delta = delta or 1
            vpdiff += (step ushr 2)
        }

        pred = if (sign != 0) clampI16(pred - vpdiff) else clampI16(pred + vpdiff)

        idx += indexTable[delta]
        idx = idx.coerceIn(0, 88)

        state[0] = pred
        state[1] = idx
        return delta or sign
    }

    private fun decodeNibble(nibble: Int, state: IntArray) {
        var pred = state[0]
        var idx = state[1]

        val step = stepTable[idx]
        val sign = (nibble and 8) != 0
        val delta = nibble and 7

        var vpdiff = step ushr 3
        if ((delta and 4) != 0) vpdiff += step
        if ((delta and 2) != 0) vpdiff += (step ushr 1)
        if ((delta and 1) != 0) vpdiff += (step ushr 2)

        pred = if (sign) clampI16(pred - vpdiff) else clampI16(pred + vpdiff)

        idx += indexTable[delta]
        idx = idx.coerceIn(0, 88)

        state[0] = pred
        state[1] = idx
    }

    private fun readS16LE(buf: ByteArray, offset: Int): Short {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort()
    }

    private fun writeS16LE(buf: ByteArray, offset: Int, value: Short) {
        val v = value.toInt()
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun clampI16(v: Int): Int = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
}

