package com.audiobridge.client.phoneagent.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAudioGateTest {
    @Test
    fun silentChunkIsNotSubmittedForTranscription() {
        val pcm = ByteArray(WakeAudioGate.DEFAULT_MIN_BYTES) { 0 }

        val decision = WakeAudioGate.evaluate(pcm)

        assertFalse(decision.shouldTranscribe)
        assertEquals("silent", decision.reason)
        assertEquals(0, decision.avgAbs)
    }

    @Test
    fun shortChunkIsNotSubmittedForTranscription() {
        val pcm = ByteArray(128) { 0x7F }

        val decision = WakeAudioGate.evaluate(pcm)

        assertFalse(decision.shouldTranscribe)
        assertEquals("too_short", decision.reason)
    }

    @Test
    fun speechLikeChunkIsSubmittedForTranscription() {
        val pcm = ByteArray(WakeAudioGate.DEFAULT_MIN_BYTES)
        var i = 0
        while (i < pcm.size) {
            val sample = 1_200
            pcm[i] = (sample and 0xFF).toByte()
            pcm[i + 1] = ((sample shr 8) and 0xFF).toByte()
            i += 2
        }

        val decision = WakeAudioGate.evaluate(pcm)

        assertTrue(decision.shouldTranscribe)
        assertEquals("speech", decision.reason)
        assertEquals(1_200, decision.avgAbs)
    }
}
