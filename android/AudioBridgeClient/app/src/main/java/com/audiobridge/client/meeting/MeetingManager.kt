package com.audiobridge.client.meeting

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Meeting Mode Manager
 * 
 * Manages meeting session lifecycle, audio segmentation, and manifest generation.
 * Designed for continuous recording with 30-second segments.
 */
class MeetingManager(private val context: Context) {

    companion object {
        private const val TAG = "MeetingManager"
        private const val SEGMENT_DURATION_MS = 30_000L  // 30 seconds per segment
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_FRAME = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8000  // 3200 bytes per 100ms
    }

    // Meeting state
    private val isMeetingActive = AtomicBoolean(false)
    private val currentMeetingId = AtomicReference<String?>(null)
    private val segmentCounter = AtomicInteger(0)
    private val currentSegmentBytes = AtomicLong(0)
    private val segmentStartTime = AtomicLong(0)

    // Storage paths
    private var meetingsDir: File? = null
    private var currentMeetingDir: File? = null
    private var currentSegmentFile: FileOutputStream? = null

    // Callbacks
    var onMeetingStarted: ((meetingId: String) -> Unit)? = null
    var onMeetingEnded: ((meetingId: String) -> Unit)? = null
    var onSegmentSealed: ((segmentId: String, seq: Int, file: File) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    val isActive: Boolean get() = isMeetingActive.get()
    val meetingId: String? get() = currentMeetingId.get()

    /**
     * Initialize the meetings storage directory
     */
    fun initialize(): Boolean {
        return try {
            meetingsDir = File(context.filesDir, "meetings").also {
                if (!it.exists()) {
                    it.mkdirs()
                }
            }
            Log.i(TAG, "Meetings directory initialized: ${meetingsDir?.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize meetings directory", e)
            onError?.invoke("Failed to initialize storage: ${e.message}")
            false
        }
    }

    /**
     * Start a new meeting session
     */
    fun startMeeting(): String? {
        if (isMeetingActive.get()) {
            Log.w(TAG, "Meeting already active: ${currentMeetingId.get()}")
            return currentMeetingId.get()
        }

        val meetingId = "mtg-${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val meetingDir = File(meetingsDir, meetingId)

        try {
            meetingDir.mkdirs()
            val audioDir = File(meetingDir, "audio/raw")
            audioDir.mkdirs()
            
            currentMeetingDir = meetingDir
            currentMeetingId.set(meetingId)
            segmentCounter.set(0)
            isMeetingActive.set(true)

            // Create initial meeting manifest
            writeMeetingManifest(meetingId, "active")

            Log.i(TAG, "Meeting started: $meetingId")
            onMeetingStarted?.invoke(meetingId)
            
            return meetingId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start meeting", e)
            onError?.invoke("Failed to start meeting: ${e.message}")
            return null
        }
    }

    /**
     * End the current meeting session
     */
    fun endMeeting(): Boolean {
        if (!isMeetingActive.get()) {
            Log.w(TAG, "No active meeting to end")
            return false
        }

        val meetingId = currentMeetingId.get() ?: return false

        try {
            // Seal current segment if any
            sealCurrentSegment()

            // Update manifest to indicate meeting ended
            writeMeetingManifest(meetingId, "ended")

            isMeetingActive.set(false)
            Log.i(TAG, "Meeting ended: $meetingId")
            onMeetingEnded?.invoke(meetingId)

            currentMeetingId.set(null)
            currentMeetingDir = null
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end meeting", e)
            onError?.invoke("Failed to end meeting: ${e.message}")
            return false
        }
    }

    /**
     * Write PCM data to the current segment
     * Returns true if segment was sealed
     */
    fun writePcmData(data: ByteArray): Boolean {
        if (!isMeetingActive.get()) return false

        val meetingDir = currentMeetingDir ?: return false
        val now = System.currentTimeMillis()

        try {
            // Start new segment if needed
            if (currentSegmentFile == null) {
                startNewSegment(now)
            }

            // Write data
            currentSegmentFile?.write(data)
            currentSegmentBytes.addAndGet(data.size.toLong())

            // Check if segment duration exceeded
            val elapsed = now - segmentStartTime.get()
            if (elapsed >= SEGMENT_DURATION_MS) {
                sealCurrentSegment()
                return true
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write PCM data", e)
            onError?.invoke("Failed to write audio: ${e.message}")
            return false
        }
    }

    private fun startNewSegment(startTime: Long) {
        val seq = segmentCounter.incrementAndGet()
        val meetingDir = currentMeetingDir ?: return
        val segmentFile = File(meetingDir, "audio/raw/seg-${seq.toString().padStart(4, '0')}.pcm")
        
        currentSegmentFile = FileOutputStream(segmentFile)
        currentSegmentBytes.set(0)
        segmentStartTime.set(startTime)
        
        Log.d(TAG, "Started segment $seq at $startTime")
    }

    private fun sealCurrentSegment() {
        val file = currentSegmentFile ?: return
        val seq = segmentCounter.get()
        
        try {
            file.flush()
            file.close()
            
            val meetingDir = currentMeetingDir ?: return
            val segmentFile = File(meetingDir, "audio/raw/seg-${seq.toString().padStart(4, '0')}.pcm")
            
            if (segmentFile.exists() && segmentFile.length() > 0) {
                // Write segment manifest
                writeSegmentManifest(seq, segmentFile)
                
                Log.i(TAG, "Segment $seq sealed: ${segmentFile.length()} bytes")
                onSegmentSealed?.invoke("seg-$seq", seq, segmentFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seal segment", e)
        } finally {
            currentSegmentFile = null
        }
    }

    private fun writeSegmentManifest(seq: Int, segmentFile: File) {
        val meetingDir = currentMeetingDir ?: return
        val manifestFile = File(meetingDir, "audio/raw/seg-${seq.toString().padStart(4, '0')}.json")
        
        val manifest = JSONObject().apply {
            put("segment_id", "seg-$seq")
            put("seq", seq)
            put("file", segmentFile.name)
            put("size_bytes", segmentFile.length())
            put("duration_ms", System.currentTimeMillis() - segmentStartTime.get())
            put("sample_rate", SAMPLE_RATE)
            put("channels", CHANNELS)
            put("bits_per_sample", BITS_PER_SAMPLE)
            put("sealed_at", System.currentTimeMillis())
        }
        
        manifestFile.writeText(manifest.toString(2))
    }

    private fun writeMeetingManifest(meetingId: String, status: String) {
        val meetingDir = currentMeetingDir ?: return
        val manifestFile = File(meetingDir, "meta/meeting.json")
        manifestFile.parentFile?.mkdirs()
        
        val manifest = JSONObject().apply {
            put("meeting_id", meetingId)
            put("status", status)
            put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()))
            put("total_segments", segmentCounter.get())
        }
        
        manifestFile.writeText(manifest.toString(2))
    }

    /**
     * Get the upload manifest for all segments
     */
    fun getUploadManifest(): JSONObject? {
        val meetingDir = currentMeetingDir ?: return null
        val audioDir = File(meetingDir, "audio/raw")
        
        val segments = JSONArray()
        audioDir.listFiles()
            ?.filter { it.extension == "pcm" }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEachIndexed { idx, file ->
                segments.put(JSONObject().apply {
                    put("seq", idx + 1)
                    put("file", file.name)
                    put("size_bytes", file.length())
                })
            }
        
        return JSONObject().apply {
            put("meeting_id", currentMeetingId.get())
            put("total_segments", segments.length())
            put("segments", segments)
        }
    }

    /**
     * List all local meetings
     */
    fun listLocalMeetings(): List<JSONObject> {
        val meetings = mutableListOf<JSONObject>()
        meetingsDir?.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { meetingDir ->
                val manifestFile = File(meetingDir, "meta/meeting.json")
                if (manifestFile.exists()) {
                    try {
                        meetings.put(JSONObject(manifestFile.readText()))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read manifest for ${meetingDir.name}")
                    }
                }
            }
        return meetings.sortedByDescending { it.optString("created_at") }
    }

    /**
     * Get storage statistics
     */
    fun getStorageStats(): StorageStats {
        var totalBytes = 0L
        var totalMeetings = 0
        var oldestMeetingTime = Long.MAX_VALUE
        
        meetingsDir?.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { meetingDir ->
                totalMeetings++
                meetingDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { 
                        totalBytes += it.length()
                        if (it.lastModified() < oldestMeetingTime) {
                            oldestMeetingTime = it.lastModified()
                        }
                    }
            }
        
        return StorageStats(
            totalMeetings = totalMeetings,
            totalBytes = totalBytes,
            oldestMeetingAgeMs = if (oldestMeetingTime == Long.MAX_VALUE) 0 
                else System.currentTimeMillis() - oldestMeetingTime
        )
    }

    data class StorageStats(
        val totalMeetings: Int,
        val totalBytes: Long,
        val oldestMeetingAgeMs: Long
    ) {
        val totalMb: Double get() = totalBytes / (1024.0 * 1024.0)
    }
}
