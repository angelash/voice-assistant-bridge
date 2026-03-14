package com.audiobridge.client.audio

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PCM Distribution Bus
 * 
 * Distributes PCM audio data to multiple consumers from a single AudioRecord source.
 * This ensures KWS, STT, and disk recording all share the same microphone input.
 * 
 * Design principle: Only ONE AudioRecord instance is allowed at any time.
 */
class PcmDistributionBus {

    companion object {
        private const val TAG = "PcmDistributionBus"
    }

    /**
     * Consumer interface for PCM data
     */
    interface PcmConsumer {
        val name: String
        val enabled: Boolean
        fun onPcmData(data: ByteArray)
        fun flush() {}
    }

    private val consumers = CopyOnWriteArrayList<PcmConsumer>()
    private val isDistributing = AtomicBoolean(false)
    
    // Single audio record reference (externally managed)
    @Volatile private var activeCapture: AudioRecordCapture? = null

    val isRunning: Boolean get() = isDistributing.get()
    val consumerCount: Int get() = consumers.size

    /**
     * Register a PCM consumer
     */
    fun registerConsumer(consumer: PcmConsumer) {
        if (!consumers.contains(consumer)) {
            consumers.add(consumer)
            Log.i(TAG, "Registered consumer: ${consumer.name}, total: ${consumers.size}")
        }
    }

    /**
     * Unregister a PCM consumer
     */
    fun unregisterConsumer(consumer: PcmConsumer) {
        consumers.remove(consumer)
        Log.i(TAG, "Unregistered consumer: ${consumer.name}, remaining: ${consumers.size}")
    }

    /**
     * Start distribution from an AudioRecordCapture
     * Returns false if distribution is already active
     */
    fun startDistribution(capture: AudioRecordCapture): Boolean {
        if (isDistributing.getAndSet(true)) {
            Log.w(TAG, "Distribution already active")
            return false
        }

        activeCapture = capture
        
        // Set up the frame callback
        capture.onFrameAvailable = { data ->
            distributeToConsumers(data)
        }

        Log.i(TAG, "Started PCM distribution")
        return true
    }

    /**
     * Stop distribution
     */
    fun stopDistribution() {
        activeCapture?.onFrameAvailable = null
        activeCapture = null
        isDistributing.set(false)

        // Flush all consumers
        consumers.forEach { it.flush() }

        Log.i(TAG, "Stopped PCM distribution")
    }

    private fun distributeToConsumers(data: ByteArray) {
        consumers.forEach { consumer ->
            if (consumer.enabled) {
                try {
                    consumer.onPcmData(data)
                } catch (e: Exception) {
                    Log.e(TAG, "Consumer ${consumer.name} error: ${e.message}")
                }
            }
        }
    }

    /**
     * Check if any consumer is currently active (needs audio)
     */
    fun hasActiveConsumer(): Boolean {
        return consumers.any { it.enabled }
    }

    /**
     * Get names of active consumers
     */
    fun getActiveConsumerNames(): List<String> {
        return consumers.filter { it.enabled }.map { it.name }
    }
}


/**
 * Disk Writer Consumer
 * Writes PCM data to disk via MeetingManager
 */
class DiskWriterConsumer(
    private val meetingManager: com.audiobridge.client.meeting.MeetingManager
) : PcmDistributionBus.PcmConsumer {

    override val name: String = "disk-writer"
    override var enabled: Boolean = false
    private var bytesWritten = 0L

    override fun onPcmData(data: ByteArray) {
        if (!enabled) return
        meetingManager.writePcmData(data)
        bytesWritten += data.size
    }

    override fun flush() {
        bytesWritten = 0
    }

    fun getBytesWritten(): Long = bytesWritten
}


/**
 * STT Forwarder Consumer
 * Forwards PCM data to the STT engine (iFlytek ASR)
 */
class SttForwarderConsumer(
    private val onPcmCallback: (ByteArray) -> Unit
) : PcmDistributionBus.PcmConsumer {

    override val name: String = "stt-forwarder"
    override var enabled: Boolean = false

    override fun onPcmData(data: ByteArray) {
        if (!enabled) return
        onPcmCallback(data)
    }
}


/**
 * KWS Detector Consumer
 * Receives PCM data for wake word detection
 */
class KwsDetectorConsumer : PcmDistributionBus.PcmConsumer {

    override val name: String = "kws-detector"
    override var enabled: Boolean = false
    
    // KWS callback when wake word is detected
    var onWakeWordDetected: (() -> Unit)? = null

    override fun onPcmData(data: ByteArray) {
        if (!enabled) return
        // TODO: Implement actual KWS detection
        // For now, this is just a skeleton that passes data through
        detectWakeWord(data)
    }

    private fun detectWakeWord(data: ByteArray) {
        // Placeholder for wake word detection
        // Will be implemented with sherpa-onnx or similar
    }
}
