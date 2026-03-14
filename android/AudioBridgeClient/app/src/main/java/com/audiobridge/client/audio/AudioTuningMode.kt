package com.audiobridge.client.audio

/**
 * 音频调优模式（用于 A/B 对比测试）
 *
 * - LEGACY：旧版方案（较小 AudioTrack buffer、无预缓冲、欠载时 sleep、无音频线程提权）
 * - ROBUST：新版方案（更大 buffer、预缓冲、音频线程优先级、欠载不额外 sleep）
 */
enum class AudioTuningMode(val id: Int) {
    LEGACY(0),
    ROBUST(1);

    companion object {
        fun fromId(id: Int): AudioTuningMode {
            return entries.firstOrNull { it.id == id } ?: ROBUST
        }
    }
}

