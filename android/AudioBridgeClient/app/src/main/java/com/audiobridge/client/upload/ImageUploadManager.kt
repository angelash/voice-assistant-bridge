package com.audiobridge.client.upload

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.audiobridge.client.FriendlyErrors
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Image Upload Manager
 * 
 * M5: Manages image upload for meeting photos with thumbnail generation.
 * 
 * Features:
 * - Camera/gallery image capture integration
 * - Automatic thumbnail generation
 * - Upload with metadata (device, timestamp, dimensions)
 * - Retry with exponential backoff
 * - Status callbacks for UI updates
 */
class ImageUploadManager(
    baseUrl: String,
    private val deviceId: String,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    companion object {
        private const val TAG = "ImageUploadManager"
        
        // Thumbnail settings
        const val THUMBNAIL_MAX_SIZE = 256
        const val THUMBNAIL_QUALITY = 85
        
        // Retry configuration
        const val MAX_RETRIES = 3
        const val INITIAL_BACKOFF_MS = 1000L
        
        // Content types
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val IMAGE_MEDIA_TYPE = "image/jpeg".toMediaType()
        
        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Image upload task
     */
    data class ImageTask(
        val imageId: String,
        val meetingId: String,
        val seq: Int,
        val originalFile: File,
        val thumbnailFile: File? = null,
        val filename: String,
        val capturedAt: String,
        val width: Int,
        val height: Int,
        val format: String = "jpeg",
        var attempts: Int = 0,
        var status: Status = Status.PENDING,
        var lastError: String? = null
    ) {
        enum class Status {
            PENDING,
            GENERATING_THUMBNAIL,
            UPLOADING,
            UPLOADED,
            FAILED
        }
    }

    // Queue state
    private val queue = CopyOnWriteArrayList<ImageTask>()
    @Volatile
    private var baseUrl: String = baseUrl.trimEnd('/')
    private val isProcessing = AtomicBoolean(false)
    private val totalUploaded = AtomicInteger(0)
    private val totalFailed = AtomicInteger(0)
    private val seqCounter = AtomicInteger(0)

    // Callbacks
    var onTaskStatusChanged: ((task: ImageTask) -> Unit)? = null
    var onQueueProgress: ((pending: Int, uploaded: Int, failed: Int) -> Unit)? = null
    var onAllTasksComplete: (() -> Unit)? = null

    val pendingCount: Int get() = queue.count { it.status == ImageTask.Status.PENDING }
    val uploadedCount: Int get() = totalUploaded.get()
    val failedCount: Int get() = totalFailed.get()
    val isQueueActive: Boolean get() = isProcessing.get() || pendingCount > 0

    /**
     * Update upload target base URL (e.g., LAN/Tunnel route switch).
     */
    fun setBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (normalized.isBlank()) return
        baseUrl = normalized
        Log.i(TAG, "Image upload base URL updated: $baseUrl")
    }

    /**
     * Add an image to the upload queue
     * 
     * @param imageFile The image file to upload
     * @param meetingId The meeting ID to associate with
     * @param filename Original filename
     * @return The created ImageTask
     */
    fun addImage(imageFile: File, meetingId: String, filename: String? = null): ImageTask {
        val seq = seqCounter.incrementAndGet()
        val imageId = "img-${UUID.randomUUID().toString().replace("-", "").take(24)}"
        
        // Get image dimensions
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        val format = when (options.outMimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpeg"
        }
        
        val capturedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(imageFile.lastModified()))
        
        val task = ImageTask(
            imageId = imageId,
            meetingId = meetingId,
            seq = seq,
            originalFile = imageFile,
            filename = filename ?: imageFile.name,
            capturedAt = capturedAt,
            width = width,
            height = height,
            format = format
        )
        
        queue.add(task)
        Log.i(TAG, "Image added to queue: $imageId, ${width}x${height}")
        
        onTaskStatusChanged?.invoke(task)
        onQueueProgress?.invoke(pendingCount, uploadedCount, failedCount)
        
        return task
    }

    /**
     * Process the upload queue
     */
    fun processQueue() {
        if (isProcessing.getAndSet(true)) {
            return
        }
        
        Thread {
            while (queue.any { it.status == ImageTask.Status.PENDING }) {
                val task = queue.firstOrNull { it.status == ImageTask.Status.PENDING }
                    ?: break
                
                try {
                    uploadImage(task)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload image ${task.imageId}", e)
                    task.status = ImageTask.Status.FAILED
                    task.lastError = FriendlyErrors.throwableMessage(e, action = "上传图片")
                    totalFailed.incrementAndGet()
                }
                
                onTaskStatusChanged?.invoke(task)
                onQueueProgress?.invoke(pendingCount, uploadedCount, failedCount)
            }
            
            isProcessing.set(false)
            
            if (pendingCount == 0) {
                onAllTasksComplete?.invoke()
            }
        }.start()
    }

    private fun uploadImage(task: ImageTask) {
        task.status = ImageTask.Status.UPLOADING
        onTaskStatusChanged?.invoke(task)
        
        val url = "${baseUrl.trimEnd('/')}/v2/meetings/${task.meetingId}/images:upload"
        
        // Read image bytes
        val imageBytes = task.originalFile.readBytes()
        
        // Build multipart request
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", task.filename, imageBytes.toRequestBody(IMAGE_MEDIA_TYPE))
            .addFormDataPart("filename", task.filename)
            .addFormDataPart("captured_at", task.capturedAt)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("width", task.width.toString())
            .addFormDataPart("height", task.height.toString())
            .addFormDataPart("format", task.format)
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        
        var lastException: Exception? = null
        
        for (attempt in 1..MAX_RETRIES) {
            task.attempts = attempt
            
            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val json = JSONObject(body.ifBlank { "{}" })
                        
                        if (json.optBoolean("ok", false)) {
                            task.status = ImageTask.Status.UPLOADED
                            totalUploaded.incrementAndGet()
                            Log.i(TAG, "Image uploaded: ${task.imageId}")
                            return
                        } else {
                            throw IOException(FriendlyErrors.jsonMessage(json, "上传图片失败，请稍后重试。"))
                        }
                    } else {
                        throw IOException(
                            FriendlyErrors.httpPayloadMessage(
                                response.code,
                                body,
                                "上传图片失败，请稍后重试。",
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Upload attempt $attempt failed for ${task.imageId}: ${e.message}")
                
                if (attempt < MAX_RETRIES) {
                    val backoff = INITIAL_BACKOFF_MS * (1 shl (attempt - 1))
                    Thread.sleep(backoff)
                }
            }
        }
        
        throw lastException ?: IOException("Upload failed after $MAX_RETRIES attempts")
    }

    /**
     * Generate a thumbnail for an image file
     * 
     * @param imageFile Source image file
     * @param outputDir Directory to save thumbnail
     * @return Generated thumbnail file or null on failure
     */
    fun generateThumbnail(imageFile: File, outputDir: File): File? {
        return try {
            // Decode with sampling for memory efficiency
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(imageFile, THUMBNAIL_MAX_SIZE, THUMBNAIL_MAX_SIZE)
            }
            
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options)
                ?: return null
            
            // Scale to exact thumbnail size
            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                THUMBNAIL_MAX_SIZE,
                THUMBNAIL_MAX_SIZE,
                true
            )
            
            // Save thumbnail
            outputDir.mkdirs()
            val thumbFile = File(outputDir, "thumb_${imageFile.nameWithoutExtension}.jpg")
            
            FileOutputStream(thumbFile).use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
            }
            
            if (bitmap != scaledBitmap) {
                bitmap.recycle()
            }
            scaledBitmap.recycle()
            
            Log.i(TAG, "Thumbnail generated: ${thumbFile.absolutePath}")
            thumbFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate thumbnail", e)
            null
        }
    }

    private fun calculateInSampleSize(file: File, reqWidth: Int, reqHeight: Int): Int {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        
        val (width, height) = options.outWidth to options.outHeight
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }

    /**
     * Cancel all pending uploads
     */
    fun cancelAll() {
        queue.forEach { task ->
            if (task.status == ImageTask.Status.PENDING || task.status == ImageTask.Status.UPLOADING) {
                task.status = ImageTask.Status.FAILED
                task.lastError = "Cancelled"
            }
        }
        isProcessing.set(false)
    }

    /**
     * Clear completed tasks from the queue
     */
    fun clearCompleted() {
        queue.removeAll { 
            it.status == ImageTask.Status.UPLOADED || it.status == ImageTask.Status.FAILED 
        }
    }

    /**
     * Get queue statistics
     */
    fun getStats(): QueueStats {
        return QueueStats(
            total = queue.size,
            pending = pendingCount,
            uploaded = uploadedCount,
            failed = failedCount
        )
    }

    data class QueueStats(
        val total: Int,
        val pending: Int,
        val uploaded: Int,
        val failed: Int
    )
}
