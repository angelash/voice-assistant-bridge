package com.audiobridge.client.phoneagent.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class PhoneAgentCaptureUiState(
    val cameraMode: String? = null,
    val wakeListening: Boolean = false,
    val statusText: String = "采集服务未运行",
    val lastFrameAtMs: Long = 0L,
    val lastWakeText: String? = null,
    val lastError: String? = null,
)

object PhoneAgentCaptureStatus {
    private val _state = MutableStateFlow(PhoneAgentCaptureUiState())
    val state: StateFlow<PhoneAgentCaptureUiState> = _state

    fun update(transform: (PhoneAgentCaptureUiState) -> PhoneAgentCaptureUiState) {
        _state.update(transform)
    }

    fun setError(message: String) {
        _state.update { current ->
            current.copy(statusText = message, lastError = message)
        }
    }

    fun clearError() {
        _state.update { current -> current.copy(lastError = null) }
    }
}
