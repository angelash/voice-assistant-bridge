package com.audiobridge.client.phoneagent.data.repository

import com.audiobridge.client.phoneagent.model.BridgeMessageStatus
import com.audiobridge.client.phoneagent.model.LocalMessageStatus

internal object PendingRequestPruner {
    private val confirmedBridgeStatuses = setOf(
        BridgeMessageStatus.NEW,
        BridgeMessageStatus.ACCEPTED,
        BridgeMessageStatus.LOCAL_REPLIED,
        BridgeMessageStatus.FORWARDED,
        BridgeMessageStatus.WAITING_OPENCLAW,
        BridgeMessageStatus.RETRYING,
        BridgeMessageStatus.OPENCLAW_RECEIVED,
        BridgeMessageStatus.DELIVERED,
        BridgeMessageStatus.FAILED,
    )

    fun shouldDropConfirmedPending(localStatus: String, bridgeStatus: String): Boolean {
        if (localStatus == LocalMessageStatus.PENDING.name || localStatus == LocalMessageStatus.SENDING.name) {
            return false
        }
        return bridgeStatus in confirmedBridgeStatuses
    }

    fun shouldDropForBridgePayload(bridgeStatus: String): Boolean {
        return bridgeStatus in confirmedBridgeStatuses
    }
}
