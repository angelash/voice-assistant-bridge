package com.audiobridge.client.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 麦克风捕获：使用 AudioRecord 捕获 48kHz/16bit/mono PCM
 */
class AudioRecordCapture {

    companion object {
        private const val TAG = "AudioRecordCapture"
    }

    private var audioRecord: AudioRecord? = null
    private val isCapturing = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var tuningMode: AudioTuningMode = AudioTuningMode.ROBUST

    /** 当收到完整的 20ms PCM 帧时触发 */
    var onFrameAvailable: ((ByteArray) -> Unit)? = null

    /** 当捕获出错时触发 */
    var onError: ((String) -> Unit)? = null

    /** 是否正在捕获 */
    val isRunning: Boolean get() = isCapturing.get()

    fun setTuningMode(mode: AudioTuningMode) {
        tuningMode = mode
    }

    /**
     * 开始捕获
     * @return 成功返回 true，失败返回 false
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isCapturing.get()) {
            Log.w(TAG, "Already capturing")
            return true
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            onError?.invoke("无法获取合适的缓冲区大小")
            return false
        }

        // 使用较大的缓冲区以避免溢出
        val multiplier = if (tuningMode == AudioTuningMode.ROBUST) 6 else 4
        val actualBufferSize = maxOf(bufferSize, AudioConfig.BYTES_PER_FRAME * multiplier)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                actualBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("AudioRecord 初始化失败")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            isCapturing.set(true)

            captureThread = thread(name = "AudioRecordCapture") {
                captureLoop()
            }

            Log.i(TAG, "开始捕获：bufferSize=$actualBufferSize")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "启动捕获失败", e)
            onError?.invoke("启动捕获失败：${e.message}")
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }

    /**
     * 停止捕获
     */
    fun stop() {
        if (!isCapturing.get()) return

        isCapturing.set(false)
        captureThread?.interrupt()
        captureThread = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止录音异常", e)
        }

        audioRecord?.release()
        audioRecord = null

        Log.i(TAG, "已停止捕获")
    }

    private fun captureLoop() {
        if (tuningMode == AudioTuningMode.ROBUST) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Exception) {
                // ignore
            }
        }

        val frameBuffer = ByteArray(AudioConfig.BYTES_PER_FRAME)
        var offset = 0

        while (isCapturing.get()) {
            try {
                val record = audioRecord ?: break
                val bytesRead = record.read(frameBuffer, offset, frameBuffer.size - offset)

                if (bytesRead > 0) {
                    offset += bytesRead

                    // 满一帧则回调
                    if (offset >= AudioConfig.BYTES_PER_FRAME) {
                        onFrameAvailable?.invoke(frameBuffer.copyOf())
                        offset = 0
                    }
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AudioRecord 无效操作")
                    onError?.invoke("录音无效操作")
                    break
                } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord 参数错误")
                    onError?.invoke("录音参数错误")
                    break
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "捕获线程被中断")
                break
            } catch (e: Exception) {
                Log.e(TAG, "捕获异常", e)
                onError?.invoke("捕获异常：${e.message}")
                break
            }
        }

        Log.i(TAG, "捕获循环结束")
    }
}
