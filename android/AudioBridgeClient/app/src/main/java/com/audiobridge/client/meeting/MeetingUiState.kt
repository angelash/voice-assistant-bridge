package com.audiobridge.client.meeting

data class MeetingUiSnapshot(
    val active: Boolean,
    val busy: Boolean,
    val meetingId: String?,
    val statusText: String,
    val infoText: String,
    val updatedAtMs: Long,
)

object MeetingUiState {
    private val lock = Any()

    private var snapshot = MeetingUiSnapshot(
        active = false,
        busy = false,
        meetingId = null,
        statusText = "Idle",
        infoText = "",
        updatedAtMs = System.currentTimeMillis(),
    )

    fun update(
        active: Boolean,
        busy: Boolean,
        meetingId: String?,
        statusText: String,
        infoText: String,
    ) {
        synchronized(lock) {
            snapshot = MeetingUiSnapshot(
                active = active,
                busy = busy,
                meetingId = meetingId,
                statusText = statusText,
                infoText = infoText,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun snapshot(): MeetingUiSnapshot {
        synchronized(lock) {
            return snapshot
        }
    }
}
