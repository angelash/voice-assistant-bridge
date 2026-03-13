package com.audiobridge.client.ws

import android.util.Log
import com.audiobridge.client.abp.AbpBinaryFrame
import com.audiobridge.client.abp.AbpControlJson
import com.audiobridge.client.abp.AbpControlMessage
import com.audiobridge.client.abp.AbpStreamId
import com.audiobridge.client.abp.ImaAdpcm
import com.audiobridge.client.abp.HelloCapabilities
import com.audiobridge.client.abp.HelloMessage
import com.audiobridge.client.abp.PingMessage
import com.audiobridge.client.abp.PongMessage
import com.audiobridge.client.abp.WelcomeMessage
import com.audiobridge.client.abp.ConfigMessage
import com.audiobridge.client.audio.AudioConfig
import com.audiobridge.client.audio.Pcm16SilenceGate
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ABP WebSocket 客户端：支持控制消息和音频帧
 */
class AbpWebSocketClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) {
    companion object {
        private const val TAG = "AbpWebSocketClient"
    }

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    interface Callbacks {
        fun onState(state: State)
        fun onWelcome(welcome: WelcomeMessage)
        fun onError(message: String)
        fun onLog(line: String)
        /** 收到下行音频帧（系统声音 -> Android）*/
        fun onDownlinkFrame(pcmPayload: ByteArray)
        /** 收到其他控制消息 */
        fun onControlMessage(message: AbpControlMessage)
    }

    private var ws: WebSocket? = null
    private var state: State = State.DISCONNECTED
    private var currentCallbacks: Callbacks? = null

    // 协商结果（Welcome.selected）
    private var selectedCodecName: String = "pcm"

    // 上行序列号
    private val uplinkSeq = AtomicLong(0)

    // 省流：上行静音门（VAD/DTX）- 可通过 config 消息动态修改参数
    private val _uplinkSilenceGate = Pcm16SilenceGate(thresholdAvgAbs = 120, minSilentFramesToSuppress = 10)
    
    /** 上行静音门（可用于监控或动态调整参数） */
    val uplinkSilenceGate: Pcm16SilenceGate get() = _uplinkSilenceGate

    // 流量/帧统计（网络层：按“实际发送/接收”计）
    private val uplinkFramesSent = AtomicLong(0)
    private val uplinkFramesSuppressed = AtomicLong(0)
    private val uplinkPayloadBytesSent = AtomicLong(0)
    private val downlinkFramesReceived = AtomicLong(0)
    private val downlinkPayloadBytesReceived = AtomicLong(0)
    
    // 心跳定时器
    private var heartbeatTimer: Timer? = null
    private var heartbeatIntervalMs: Long = 5000 // 默认 5 秒

    val isConnected: Boolean get() = state == State.CONNECTED
    val selectedCodec: String get() = selectedCodecName
    val uplinkFramesSentCount: Long get() = uplinkFramesSent.get()
    val uplinkFramesSuppressedCount: Long get() = uplinkFramesSuppressed.get()
    val uplinkPayloadBytesSentCount: Long get() = uplinkPayloadBytesSent.get()
    val downlinkFramesReceivedCount: Long get() = downlinkFramesReceived.get()
    val downlinkPayloadBytesReceivedCount: Long get() = downlinkPayloadBytesReceived.get()

    fun connect(
        host: String,
        token: String?,
        deviceId: String,
        callbacks: Callbacks,
    ) {
        Log.i(TAG, "connect() called, host=$host, state=$state")
        
        if (state != State.DISCONNECTED) {
            Log.w(TAG, "Already connecting or connected, state=$state")
            return
        }
        setState(State.CONNECTING, callbacks)
        currentCallbacks = callbacks
        selectedCodecName = "pcm"
        _uplinkSilenceGate.reset()
        uplinkFramesSent.set(0)
        uplinkFramesSuppressed.set(0)
        uplinkPayloadBytesSent.set(0)
        downlinkFramesReceived.set(0)
        downlinkPayloadBytesReceived.set(0)

        // 智能构建 WebSocket URL
        val url = buildWebSocketUrl(host)
        Log.i(TAG, "Built WebSocket URL: $url")
        
        val request = Request.Builder().url(url).build()

        callbacks.onLog("Connecting $url ...")
        Log.i(TAG, "Creating WebSocket connection...")
        
        ws = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "onOpen: response=${response.code}")
                    callbacks.onLog("WS opened: ${response.code}")
                    setState(State.CONNECTED, callbacks)

                    // 重置上行序列号
                    uplinkSeq.set(0)

                    val hello = HelloMessage(
                        deviceId = deviceId,
                        token = token?.takeIf { it.isNotBlank() },
                        cap = HelloCapabilities(
                            // 优先申请 adpcm（4x 省流），兼容回退到 pcm
                            codec = arrayOf("adpcm", "pcm"),
                            sampleRate = intArrayOf(48000),
                            frameMs = intArrayOf(20),
                            uplink = true,
                            downlink = true,
                        ),
                    )
                    val helloJson = hello.toJson()
                    Log.i(TAG, "Sending hello: $helloJson")
                    webSocket.send(helloJson)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.i(TAG, "onMessage(text): $text")
                    callbacks.onLog("WS text: $text")
                    try {
                        val msg = AbpControlJson.parse(text)
                        when (msg) {
                            is WelcomeMessage -> {
                                Log.i(TAG, "Received Welcome: sessionId=${msg.sessionId}")
                                // 从 Welcome 消息获取心跳间隔并启动心跳
                                heartbeatIntervalMs = msg.server.heartbeatMs.toLong()
                                // 记录协商出的 codec（用于音频帧编解码）
                                selectedCodecName = msg.selected.codec
                                _uplinkSilenceGate.reset()
                                startHeartbeat()
                                callbacks.onWelcome(msg)
                            }
                            is PongMessage -> {
                                Log.d(TAG, "Received Pong: t=${msg.t}")
                                callbacks.onControlMessage(msg)
                            }
                            is ConfigMessage -> {
                                Log.i(TAG, "Received Config: uplinkThreshold=${msg.uplinkThreshold}, uplinkMinSilentFrames=${msg.uplinkMinSilentFrames}")
                                // 应用配置到静音门
                                msg.uplinkThreshold?.let { _uplinkSilenceGate.thresholdAvgAbs = it }
                                msg.uplinkMinSilentFrames?.let { _uplinkSilenceGate.minSilentFramesToSuppress = it }
                                callbacks.onControlMessage(msg)
                            }
                            else -> callbacks.onControlMessage(msg)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Parse control msg failed", e)
                        callbacks.onError("Parse control msg failed: ${e.message}")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // 二进制帧：ABP 音频帧
                    val result = AbpBinaryFrame.tryDecode(bytes.toByteArray())
                    result.fold(
                        onSuccess = { frame ->
                            when (frame.streamId) {
                                AbpStreamId.DOWNLINK -> {
                                    // 下行音频：系统声音 -> Android 播放
                                    downlinkFramesReceived.incrementAndGet()
                                    downlinkPayloadBytesReceived.addAndGet(frame.payload.size.toLong())

                                    val pcm = decodeToPcm(frame.payload)
                                    callbacks.onDownlinkFrame(pcm)
                                }
                                AbpStreamId.UPLINK -> {
                                    // 上行音频回显？一般不会收到
                                    Log.w(TAG, "Received unexpected uplink frame")
                                }
                            }
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Decode binary frame failed", e)
                            callbacks.onError("Decode binary frame failed: ${e.message}")
                        }
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "onFailure: ${t.message}, response=${response?.code}", t)
                    stopHeartbeat()
                    callbacks.onError("WS failure: ${t.message}")
                    callbacks.onLog("WS response: ${response?.code}")
                    setState(State.DISCONNECTED, callbacks)
                    currentCallbacks = null
                    ws = null
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "onClosing: code=$code, reason=$reason")
                    stopHeartbeat()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "onClosed: code=$code, reason=$reason")
                    stopHeartbeat()
                    callbacks.onLog("WS closed: $code $reason")
                    setState(State.DISCONNECTED, callbacks)
                    currentCallbacks = null
                    ws = null
                }
            },
        )
        Log.i(TAG, "WebSocket created, waiting for callbacks...")
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        stopHeartbeat()
        ws?.close(1000, "bye")
        ws = null
        currentCallbacks?.let { setState(State.DISCONNECTED, it) }
        currentCallbacks = null
        _uplinkSilenceGate.reset()
    }
    
    private fun startHeartbeat() {
        stopHeartbeat()
        Log.i(TAG, "Starting heartbeat timer, interval=${heartbeatIntervalMs}ms")
        heartbeatTimer = Timer("ABP-Heartbeat", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        sendPing()
                    } catch (e: Exception) {
                        Log.e(TAG, "Heartbeat ping failed", e)
                    }
                }
            }, heartbeatIntervalMs, heartbeatIntervalMs)
        }
    }
    
    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        Log.i(TAG, "Heartbeat timer stopped")
    }

    /**
     * 发送上行音频帧（麦克风 -> Windows）
     */
    fun sendUplinkFrame(pcmPayload: ByteArray, timestampSamples: Long = 0) {
        val socket = ws ?: return
        if (state != State.CONNECTED) return

        // 省流：静音连续一段时间后停止发送
        if (!_uplinkSilenceGate.shouldSend(pcmPayload)) {
            uplinkFramesSuppressed.incrementAndGet()
            return
        }

        val payloadToSend = encodeFromPcm(pcmPayload)

        val frame = AbpBinaryFrame(
            streamId = AbpStreamId.UPLINK,
            seq = uplinkSeq.incrementAndGet(),
            timestampSamples = timestampSamples,
            payload = payloadToSend,
        )

        socket.send(frame.encode().toByteString())
        uplinkFramesSent.incrementAndGet()
        uplinkPayloadBytesSent.addAndGet(payloadToSend.size.toLong())
    }

    /**
     * 发送 Ping 消息
     */
    fun sendPing() {
        val socket = ws ?: return
        if (state != State.CONNECTED) return

        val ping = PingMessage(t = System.currentTimeMillis())
        val json = ping.toJson()
        Log.d(TAG, "Sending ping: $json")
        socket.send(json)
    }

    /**
     * 发送控制消息
     */
    fun sendControlMessage(message: AbpControlMessage) {
        val socket = ws ?: return
        if (state != State.CONNECTED) return

        socket.send(message.toJson())
    }

    private fun setState(newState: State, callbacks: Callbacks) {
        Log.i(TAG, "setState: $state -> $newState")
        state = newState
        callbacks.onState(newState)
    }

    /**
     * 智能构建 WebSocket URL
     * 支持：
     * - 纯域名: "example.com" -> "ws://example.com/abp"
     * - IP:端口: "10.3.91.22:21347" -> "ws://10.3.91.22:21347/abp"
     * - 完整 URL: "ws://example.com/abp" -> 直接使用
     */
    private fun buildWebSocketUrl(host: String): String {
        Log.d(TAG, "buildWebSocketUrl: input=$host")
        
        // 如果已经是完整 URL，直接返回
        if (host.startsWith("ws://") || host.startsWith("wss://")) {
            val result = if (host.endsWith("/abp")) host else "$host/abp"
            Log.d(TAG, "buildWebSocketUrl: already has protocol, result=$result")
            return result
        }

        // 否则添加 ws:// 前缀
        val result = "ws://$host/abp"
        Log.d(TAG, "buildWebSocketUrl: added ws://, result=$result")
        return result
    }

    private fun encodeFromPcm(pcmPayload: ByteArray): ByteArray {
        return if (selectedCodecName.equals("adpcm", ignoreCase = true)) {
            ImaAdpcm.encodePcm16Mono(pcmPayload, AudioConfig.SAMPLES_PER_FRAME)
        } else {
            pcmPayload
        }
    }

    private fun decodeToPcm(payload: ByteArray): ByteArray {
        return if (selectedCodecName.equals("adpcm", ignoreCase = true)) {
            ImaAdpcm.decodeToPcm16Mono(payload, AudioConfig.SAMPLES_PER_FRAME)
        } else {
            payload
        }
    }
}
