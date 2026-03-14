package com.audiobridge.client.audio

import android.util.Log

/**
 * PCM Audio Resampler
 * 
 * Converts PCM audio between sample rates using linear interpolation.
 * Supports mono 16-bit PCM (the project's standard format).
 * 
 * Primary use case: Resampling 48kHz meeting audio to 16kHz for STT.
 */
object PcmResampler {

    private const val TAG = "PcmResampler"

    /**
     * Downsample PCM 16-bit mono audio
     * 
     * @param input Input PCM data at source sample rate
     * @param sourceRate Source sample rate (e.g., 48000)
     * @param targetRate Target sample rate (e.g., 16000)
     * @return Resampled PCM data
     */
    fun downsample(input: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (sourceRate == targetRate) {
            return input
        }

        if (sourceRate < targetRate) {
            Log.w(TAG, "Upsampling not optimized, using simple interpolation")
            return upsample(input, sourceRate, targetRate)
        }

        // Number of 16-bit samples
        val inputSamples = input.size / 2
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val outputSamples = (inputSamples / ratio).toInt()
        val output = ByteArray(outputSamples * 2)

        for (i in 0 until outputSamples) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()

            if (srcIndex * 2 + 3 < input.size) {
                // Linear interpolation between two samples
                val sample1 = getSample16(input, srcIndex)
                val sample2 = getSample16(input, srcIndex + 1)
                val frac = srcPos - srcIndex
                val interpolated = (sample1 + (sample2 - sample1) * frac).toInt().toShort()
                
                output[i * 2] = (interpolated.toInt() and 0xFF).toByte()
                output[i * 2 + 1] = (interpolated.toInt() shr 8).toByte()
            } else if (srcIndex * 2 + 1 < input.size) {
                // Last sample, no interpolation
                val sample = getSample16(input, srcIndex)
                output[i * 2] = (sample.toInt() and 0xFF).toByte()
                output[i * 2 + 1] = (sample.toInt() shr 8).toByte()
            }
        }

        return output
    }

    /**
     * Upsample PCM 16-bit mono audio (simple linear interpolation)
     */
    private fun upsample(input: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        val inputSamples = input.size / 2
        val ratio = targetRate.toDouble() / sourceRate.toDouble()
        val outputSamples = (inputSamples * ratio).toInt()
        val output = ByteArray(outputSamples * 2)

        for (i in 0 until outputSamples) {
            val srcPos = i / ratio
            val srcIndex = srcPos.toInt()

            if (srcIndex * 2 + 3 < input.size) {
                val sample1 = getSample16(input, srcIndex)
                val sample2 = getSample16(input, srcIndex + 1)
                val frac = srcPos - srcIndex
                val interpolated = (sample1 + (sample2 - sample1) * frac).toInt().toShort()
                
                output[i * 2] = (interpolated.toInt() and 0xFF).toByte()
                output[i * 2 + 1] = (interpolated.toInt() shr 8).toByte()
            } else if (srcIndex * 2 + 1 < input.size) {
                val sample = getSample16(input, minOf(srcIndex, inputSamples - 1))
                output[i * 2] = (sample.toInt() and 0xFF).toByte()
                output[i * 2 + 1] = (sample.toInt() shr 8).toByte()
            }
        }

        return output
    }

    /**
     * Get a 16-bit sample from PCM byte array (little-endian)
     */
    private fun getSample16(data: ByteArray, sampleIndex: Int): Short {
        val offset = sampleIndex * 2
        if (offset + 1 >= data.size) return 0
        val low = data[offset].toInt() and 0xFF
        val high = data[offset + 1].toInt()
        return ((high shl 8) or low).toShort()
    }

    /**
     * Calculate output size for downsampling
     */
    fun calculateOutputSize(inputBytes: Int, sourceRate: Int, targetRate: Int): Int {
        val inputSamples = inputBytes / 2
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        return ((inputSamples / ratio).toInt()) * 2
    }
}
