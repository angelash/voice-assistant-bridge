package com.audiobridge.client.meeting

data class MeetingCaptionSnapshot(
    val active: Boolean,
    val statusText: String,
    val partialText: String,
    val finalLines: List<String>,
    val updatedAtMs: Long,
)

object MeetingCaptionState {
    private val lock = Any()

    private var snapshot = MeetingCaptionSnapshot(
        active = false,
        statusText = "会议未开始",
        partialText = "",
        finalLines = emptyList(),
        updatedAtMs = System.currentTimeMillis(),
    )

    fun update(
        active: Boolean,
        statusText: String,
        partialText: String,
        finalLines: List<String>,
    ) {
        synchronized(lock) {
            snapshot = MeetingCaptionSnapshot(
                active = active,
                statusText = statusText,
                partialText = partialText,
                finalLines = finalLines.toList(),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun snapshot(): MeetingCaptionSnapshot {
        synchronized(lock) {
            return snapshot
        }
    }
}
