package com.audiobridge.client.meeting

object MeetingAudioGateState {
    private const val DEFAULT_RELEASE_HOLD_MS = 650L

    private val lock = Any()
    private var activePlaybackCount = 0
    private var holdUntilMs = 0L

    fun beginPlayback() {
        synchronized(lock) {
            activePlaybackCount += 1
            holdUntilMs = 0L
        }
    }

    fun endPlayback(holdMs: Long = DEFAULT_RELEASE_HOLD_MS) {
        synchronized(lock) {
            if (activePlaybackCount > 0) {
                activePlaybackCount -= 1
            }
            if (activePlaybackCount == 0) {
                holdUntilMs = System.currentTimeMillis() + holdMs.coerceAtLeast(0L)
            }
        }
    }

    fun isInputSuppressed(nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            return activePlaybackCount > 0 || nowMs < holdUntilMs
        }
    }

    fun clear() {
        synchronized(lock) {
            activePlaybackCount = 0
            holdUntilMs = 0L
        }
    }
}
