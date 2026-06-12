package com.audiobridge.client.phoneagent.data.repository

import com.audiobridge.client.phoneagent.model.BridgeMessageStatus
import com.audiobridge.client.phoneagent.model.LocalMessageStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRequestPrunerTest {
    @Test
    fun keepsLocalPendingMessages() {
        assertFalse(
            PendingRequestPruner.shouldDropConfirmedPending(
                LocalMessageStatus.PENDING.name,
                BridgeMessageStatus.NEW,
            )
        )
        assertFalse(
            PendingRequestPruner.shouldDropConfirmedPending(
                LocalMessageStatus.SENDING.name,
                BridgeMessageStatus.DELIVERED,
            )
        )
    }

    @Test
    fun dropsSentMessagesConfirmedByBridge() {
        assertTrue(
            PendingRequestPruner.shouldDropConfirmedPending(
                LocalMessageStatus.SENT.name,
                BridgeMessageStatus.NEW,
            )
        )
        assertTrue(
            PendingRequestPruner.shouldDropConfirmedPending(
                LocalMessageStatus.SENT.name,
                BridgeMessageStatus.DELIVERED,
            )
        )
        assertTrue(
            PendingRequestPruner.shouldDropConfirmedPending(
                LocalMessageStatus.FAILED.name,
                BridgeMessageStatus.FAILED,
            )
        )
    }

    @Test
    fun dropsOnlyKnownBridgePayloadStatuses() {
        assertTrue(PendingRequestPruner.shouldDropForBridgePayload(BridgeMessageStatus.WAITING_OPENCLAW))
        assertTrue(PendingRequestPruner.shouldDropForBridgePayload(BridgeMessageStatus.OPENCLAW_RECEIVED))
        assertFalse(PendingRequestPruner.shouldDropForBridgePayload(""))
        assertFalse(PendingRequestPruner.shouldDropForBridgePayload("UNKNOWN"))
    }
}
