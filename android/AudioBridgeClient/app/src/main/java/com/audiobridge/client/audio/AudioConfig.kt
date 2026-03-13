package com.audiobridge.client.audio

/**
 * 音频配置常量（与 ABP 协议保持一致）
 */
object AudioConfig {
    /** 采样率 */
    const val SAMPLE_RATE = 48000

    /** 通道数 */
    const val CHANNELS = 1

    /** 位深 */
    const val BITS_PER_SAMPLE = 16

    /** 帧时长（毫秒）*/
    const val FRAME_MS = 20

    /** 每帧采样数 */
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000 // 960

    /** 每帧字节数（PCM16 mono）*/
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2 // 1920
}
