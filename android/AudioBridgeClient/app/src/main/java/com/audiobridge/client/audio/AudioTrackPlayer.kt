package com.audiobridge.client.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 音频播放器：使用 AudioTrack 播放下行 PCM 音频
 */
class AudioTrackPlayer {

    companion object {
        private const val TAG = "AudioTrackPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private var playThread: Thread? = null
    private var tuningMode: AudioTuningMode = AudioTuningMode.ROBUST

    // 帧缓冲队列
    private val frameQueue = ConcurrentLinkedQueue<ByteArray>()
    private val bufferedCount = AtomicInteger(0)

    // 统计
    private val framesPlayed = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val underruns = AtomicLong(0)

    /** 当播放出错时触发 */
    var onError: ((String) -> Unit)? = null

    /** 是否正在播放 */
    val isRunning: Boolean get() = isPlaying.get()

    /** 缓冲队列中的帧数 */
    val bufferedFrames: Int get() = bufferedCount.get()

    /** 缓冲时长（毫秒）*/
    val bufferedMs: Int get() = bufferedFrames * AudioConfig.FRAME_MS

    /** 已播放帧数 */
    val playedFrames: Long get() = framesPlayed.get()

    /** 丢弃帧数 */
    val droppedFrames: Long get() = framesDropped.get()

    /** 欠载次数 */
    val underrunCount: Long get() = underruns.get()

    fun setTuningMode(mode: AudioTuningMode) {
        tuningMode = mode
    }

    /**
     * 开始播放
     */
    fun start(): Boolean {
        if (isPlaying.get()) {
            Log.w(TAG, "Already playing")
            return true
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize == AudioTrack.ERROR_BAD_VALUE || bufferSize == AudioTrack.ERROR) {
            onError?.invoke("无法获取合适的缓冲区大小")
            return false
        }

        val cfg = configForMode(tuningMode)
        val actualBufferSize = maxOf(bufferSize, AudioConfig.BYTES_PER_FRAME * cfg.audioTrackBufferFramesMultiplier)

        try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(AudioConfig.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(actualBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            
            if (cfg.performanceModeLowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            audioTrack = builder.build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                onError?.invoke("AudioTrack 初始化失败")
                audioTrack?.release()
                audioTrack = null
                return false
            }

            isPlaying.set(true)

            // 重置统计
            framesPlayed.set(0)
            framesDropped.set(0)
            underruns.set(0)
            frameQueue.clear()
            bufferedCount.set(0)

            if (cfg.playImmediately) {
                audioTrack?.play()
            }

            playThread = thread(name = "AudioTrackPlayer") {
                playLoop(cfg)
            }

            Log.i(TAG, "开始播放：bufferSize=$actualBufferSize")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "启动播放失败", e)
            onError?.invoke("启动播放失败：${e.message}")
            audioTrack?.release()
            audioTrack = null
            return false
        }
    }

    /**
     * 停止播放
     */
    fun stop() {
        if (!isPlaying.get()) return

        isPlaying.set(false)
        playThread?.interrupt()
        playThread = null

        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止播放异常", e)
        }

        audioTrack?.release()
        audioTrack = null
        frameQueue.clear()
        bufferedCount.set(0)

        Log.i(TAG, "已停止播放")
    }

    /**
     * 写入 PCM 帧到播放队列
     */
    fun writeFrame(pcmFrame: ByteArray) {
        if (!isPlaying.get()) return

        val cfg = configForMode(tuningMode)

        // 防止缓冲区溢出
        if (cfg.dropManyWhenOverrun) {
            while (bufferedCount.get() >= cfg.maxBufferFrames) {
                val dropped = frameQueue.poll() // 丢弃最老的帧
                if (dropped != null) {
                    bufferedCount.decrementAndGet()
                    framesDropped.incrementAndGet()
                } else {
                    break
                }
            }
        } else {
            if (bufferedCount.get() >= cfg.maxBufferFrames) {
                val dropped = frameQueue.poll()
                if (dropped != null) {
                    bufferedCount.decrementAndGet()
                    framesDropped.incrementAndGet()
                }
            }
        }

        frameQueue.offer(pcmFrame)
        bufferedCount.incrementAndGet()
    }

    private fun playLoop(cfg: PlaybackConfig) {
        if (cfg.threadPriorityAudio) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Exception) {
                // ignore
            }
        }

        val silenceFrame = ByteArray(AudioConfig.BYTES_PER_FRAME)
        var playbackStarted = cfg.playImmediately

        while (isPlaying.get()) {
            try {
                val track = audioTrack ?: break
                
                // 预缓冲：先攒几帧再 play，减少起始/抖动时的欠载
                if (!playbackStarted) {
                    if (cfg.prebufferFrames > 0 && bufferedCount.get() < cfg.prebufferFrames) {
                        Thread.sleep(5)
                        continue
                    }
                    track.play()
                    playbackStarted = true
                }

                val frame = frameQueue.poll()
                if (frame != null) {
                    bufferedCount.decrementAndGet()
                }

                if (frame != null) {
                    val written = track.write(frame, 0, frame.size)
                    if (written > 0) {
                        framesPlayed.incrementAndGet()
                    } else if (written == AudioTrack.ERROR_INVALID_OPERATION) {
                        Log.e(TAG, "AudioTrack 无效操作")
                        onError?.invoke("播放无效操作")
                        break
                    } else if (written == AudioTrack.ERROR_BAD_VALUE) {
                        Log.e(TAG, "AudioTrack 参数错误")
                        onError?.invoke("播放参数错误")
                        break
                    }
                } else {
                    // 缓冲区空，播放静音以保持流畅
                    underruns.incrementAndGet()
                    track.write(silenceFrame, 0, silenceFrame.size)
                    if (cfg.sleepOnUnderrun) {
                        Thread.sleep(AudioConfig.FRAME_MS.toLong() / 2)
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "播放线程被中断")
                break
            } catch (e: Exception) {
                Log.e(TAG, "播放异常", e)
                onError?.invoke("播放异常：${e.message}")
                break
            }
        }

        Log.i(TAG, "播放循环结束")
    }

    private data class PlaybackConfig(
        val maxBufferFrames: Int,
        val prebufferFrames: Int,
        val audioTrackBufferFramesMultiplier: Int,
        val threadPriorityAudio: Boolean,
        val performanceModeLowLatency: Boolean,
        val sleepOnUnderrun: Boolean,
        val playImmediately: Boolean,
        val dropManyWhenOverrun: Boolean,
    )

    private fun configForMode(mode: AudioTuningMode): PlaybackConfig {
        return when (mode) {
            AudioTuningMode.LEGACY -> PlaybackConfig(
                maxBufferFrames = 50,
                prebufferFrames = 0,
                audioTrackBufferFramesMultiplier = 4,
                threadPriorityAudio = false,
                performanceModeLowLatency = false,
                sleepOnUnderrun = true,
                playImmediately = true,
                dropManyWhenOverrun = false,
            )
            AudioTuningMode.ROBUST -> PlaybackConfig(
                maxBufferFrames = 100,
                prebufferFrames = 4,
                audioTrackBufferFramesMultiplier = 8,
                threadPriorityAudio = true,
                performanceModeLowLatency = true,
                sleepOnUnderrun = false,
                playImmediately = false,
                dropManyWhenOverrun = true,
            )
        }
    }
}
