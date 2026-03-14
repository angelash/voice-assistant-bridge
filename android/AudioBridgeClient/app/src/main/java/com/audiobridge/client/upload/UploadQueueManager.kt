package com.audiobridge.client.upload

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Upload Queue Manager
 * 
 * M2: Manages audio segment upload with exponential backoff and retry logic.
 * 
 * Features:
 * - Exponential backoff (1s, 2s, 4s, 8s, 16s max)
 * - Idempotent segment IDs
 * - Concurrent upload limits
 * - Automatic retry on failure
 * - Status callbacks for UI updates
 */
class UploadQueueManager(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    companion object {
        private const val TAG = "UploadQueueManager"
        
        // Backoff configuration
        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 16000L
        const val BACKOFF_MULTIPLIER = 2.0
        
        // Retry limits
        const val MAX_RETRIES = 5
        
        // Concurrent upload limit
        const val MAX_CONCURRENT_UPLOADS = 3
        
        // Content types
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val AUDIO_MEDIA_TYPE = "application/octet-stream".toMediaType()
        
        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Upload task representing a segment to upload
     */
    data class UploadTask(
        val segmentId: String,
        val meetingId: String,
        val seq: Int,
        val file: File,
        val checksum: String,
        var attempts: Int = 0,
        var nextAttemptAt: Long = System.currentTimeMillis(),
        var status: Status = Status.PENDING,
        var lastError: String? = null
    ) {
        enum class Status {
            PENDING,
            UPLOADING,
            UPLOADED,
            FAILED,
            RETRYING
        }
    }

    // Queue state
    private val queue = CopyOnWriteArrayList<UploadTask>()
    private val isProcessing = AtomicBoolean(false)
    private val activeUploads = AtomicInteger(0)
    private val totalUploaded = AtomicInteger(0)
    private val totalFailed = AtomicInteger(0)

    // Callbacks
    var onTaskStatusChanged: ((task: UploadTask) -> Unit)? = null
    var onQueueProgress: ((pending: Int, uploaded: Int, failed: Int) -> Unit)? = null
    var onAllTasksComplete: (() -> Unit)? = null

    val pendingCount: Int get() = queue.count { it.status == UploadTask.Status.PENDING || it.status == UploadTask.Status.RETRYING }
    val uploadedCount: Int get() = totalUploaded.get()
    val failedCount: Int get() = totalFailed.get()
    val isQueueActive: Boolean get() = isProcessing.get() || pendingCount > 0

    /**
     * Add a segment to the upload queue
     */
    fun enqueue(
        meetingId: String,
        seq: Int,
        file: File,
        checksum: String
    ): UploadTask {
        val segmentId = "seg-${meetingId.take(8)}-${seq.toString().padStart(4, '0')}"
        
        // Check if already in queue
        val existing = queue.find { it.segmentId == segmentId }
        if (existing != null) {
            Log.d(TAG, "Segment already in queue: $segmentId")
            return existing
        }
        
        val task = UploadTask(
            segmentId = segmentId,
            meetingId = meetingId,
            seq = seq,
            file = file,
            checksum = checksum
        )
        
        queue.add(task)
        Log.i(TAG, "Enqueued segment: $segmentId, file=${file.name}, size=${file.length()}")
        
        // Start processing if not already
        startProcessing()
        
        return task
    }

    /**
     * Add multiple segments to the queue
     */
    fun enqueueAll(meetingId: String, segments: List<Pair<Int, File>>) {
        segments.forEach { (seq, file) ->
            val checksum = computeChecksum(file)
            enqueue(meetingId, seq, file, checksum)
        }
    }

    /**
     * Start processing the queue
     */
    fun startProcessing() {
        if (isProcessing.getAndSet(true)) {
            return  // Already processing
        }
        
        Log.i(TAG, "Starting upload queue processing")
        processQueue()
    }

    /**
     * Stop processing (waits for current uploads to complete)
     */
    fun stopProcessing() {
        isProcessing.set(false)
        Log.i(TAG, "Stopping upload queue processing")
    }

    /**
     * Retry a failed task
     */
    fun retry(task: UploadTask) {
        if (task.status == UploadTask.Status.FAILED) {
            task.status = UploadTask.Status.RETRYING
            task.nextAttemptAt = System.currentTimeMillis()
            onTaskStatusChanged?.invoke(task)
            
            if (!isProcessing.get()) {
                startProcessing()
            }
        }
    }

    /**
     * Retry all failed tasks
     */
    fun retryAllFailed() {
        queue.filter { it.status == UploadTask.Status.FAILED }.forEach { retry(it) }
    }

    /**
     * Clear completed tasks from queue
     */
    fun clearCompleted() {
        queue.removeAll { it.status == UploadTask.Status.UPLOADED }
    }

    /**
     * Get all tasks
     */
    fun getTasks(): List<UploadTask> = queue.toList()

    /**
     * Get task by segment ID
     */
    fun getTask(segmentId: String): UploadTask? = queue.find { it.segmentId == segmentId }

    private fun processQueue() {
        while (isProcessing.get() && activeUploads.get() < MAX_CONCURRENT_UPLOADS) {
            val now = System.currentTimeMillis()
            
            // Find next task to process
            val task = queue.find { 
                (it.status == UploadTask.Status.PENDING || it.status == UploadTask.Status.RETRYING)
                && it.nextAttemptAt <= now
                && it.attempts < MAX_RETRIES
            }
            
            if (task == null) {
                // No more tasks ready
                if (activeUploads.get() == 0 && pendingCount == 0) {
                    // All done
                    isProcessing.set(false)
                    onAllTasksComplete?.invoke()
                    Log.i(TAG, "Upload queue complete")
                }
                break
            }
            
            // Start upload
            activeUploads.incrementAndGet()
            task.status = UploadTask.Status.UPLOADING
            task.attempts++
            onTaskStatusChanged?.invoke(task)
            
            uploadAsync(task)
        }
        
        // Report progress
        onQueueProgress?.invoke(pendingCount, uploadedCount, failedCount)
    }

    private fun uploadAsync(task: UploadTask) {
        Thread {
            try {
                val success = uploadSegment(task)
                
                if (success) {
                    task.status = UploadTask.Status.UPLOADED
                    totalUploaded.incrementAndGet()
                    Log.i(TAG, "Upload successful: ${task.segmentId}")
                } else {
                    handleUploadFailure(task, "Upload returned failure")
                }
            } catch (e: Exception) {
                handleUploadFailure(task, e.message ?: "Unknown error")
            } finally {
                activeUploads.decrementAndGet()
                onTaskStatusChanged?.invoke(task)
                
                // Continue processing queue
                if (isProcessing.get()) {
                    processQueue()
                }
            }
        }.start()
    }

    private fun handleUploadFailure(task: UploadTask, error: String) {
        task.lastError = error
        
        if (task.attempts >= MAX_RETRIES) {
            task.status = UploadTask.Status.FAILED
            totalFailed.incrementAndGet()
            Log.e(TAG, "Upload failed permanently: ${task.segmentId}, error=$error")
        } else {
            // Schedule retry with exponential backoff
            task.status = UploadTask.Status.RETRYING
            val backoffMs = calculateBackoff(task.attempts)
            task.nextAttemptAt = System.currentTimeMillis() + backoffMs
            Log.w(TAG, "Upload failed, will retry in ${backoffMs}ms: ${task.segmentId}, error=$error")
        }
    }

    private fun calculateBackoff(attempts: Int): Long {
        val backoff = INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, attempts - 1.0).toLong()
        return minOf(backoff, MAX_BACKOFF_MS)
    }

    private fun uploadSegment(task: UploadTask): Boolean {
        val url = "$baseUrl/v2/meetings/${task.meetingId}/audio:upload"
        
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("segment_id", task.segmentId)
            .addFormDataPart("seq", task.seq.toString())
            .addFormDataPart("checksum", task.checksum)
            .addFormDataPart("audio", task.file.name, task.file.asRequestBody(AUDIO_MEDIA_TYPE))
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(multipartBody)
            .build()
        
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Upload HTTP error: ${response.code}")
                return false
            }
            
            val body = response.body?.string()
            val json = JSONObject(body ?: "{}")
            return json.optBoolean("ok", false)
        }
    }

    private fun computeChecksum(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}


/**
 * Upload Status for UI display
 */
data class UploadStatus(
    val pendingCount: Int,
    val uploadedCount: Int,
    val failedCount: Int,
    val activeUploads: Int,
    val isUploading: Boolean
) {
    val totalCount: Int get() = pendingCount + uploadedCount + failedCount
    val progressPercent: Int get() = if (totalCount == 0) 0 else (uploadedCount * 100 / totalCount)
}
