package com.audiobridge.client.phoneagent.data.api

import com.audiobridge.client.phoneagent.model.BridgeMessageStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeApiParserTest {
    @Test
    fun parseSubmitKeepsLocalReplyAndStatus() {
        val payload = JSONObject(
            """
            {
              "ok": true,
              "message_id": "msg-1",
              "session_id": "daily-agent-20260610",
              "client_id": "android-phone",
              "status": "WAITING_OPENCLAW",
              "local_reply": "已转发给龙虾大脑，正在处理。",
              "local_source_label": "本地接线员"
            }
            """.trimIndent()
        )

        val parsed = BridgeApiParser.parseSubmit(payload)

        assertEquals("msg-1", parsed.messageId)
        assertEquals(BridgeMessageStatus.WAITING_OPENCLAW, parsed.status)
        assertEquals("已转发给龙虾大脑，正在处理。", parsed.localReply)
        assertEquals(1, parsed.replies.size)
        assertEquals("quick_reply", parsed.replies.first().kind)
    }

    @Test
    fun parseStatusExtractsFinalReply() {
        val payload = JSONObject(
            """
            {
              "ok": true,
              "message_id": "msg-2",
              "session_id": "daily-agent-20260610",
              "client_id": "android-phone",
              "status": "DELIVERED",
              "messages": [
                {"source": "local-operator", "source_label": "本地接线员", "kind": "quick_reply", "text": "收到。"},
                {"source": "openclaw", "source_label": "龙虾大脑", "kind": "final_reply", "text": "最终回答"}
              ]
            }
            """.trimIndent()
        )

        val parsed = BridgeApiParser.parseStatus(payload)

        assertEquals(BridgeMessageStatus.DELIVERED, parsed.status)
        assertEquals("收到。", parsed.localReply)
        assertEquals("最终回答", parsed.replies.last().text)
    }

    @Test
    fun parseStatusTreatsJsonNullLastErrorAsNull() {
        val payload = JSONObject(
            """
            {
              "ok": true,
              "message_id": "msg-3",
              "session_id": "daily-agent-20260610",
              "client_id": "android-phone",
              "status": "DELIVERED",
              "last_error": null,
              "messages": []
            }
            """.trimIndent()
        )

        val parsed = BridgeApiParser.parseStatus(payload)

        assertNull(parsed.lastError)
    }

    @Test
    fun parseConnectedEventWithoutMessageIdIsIgnorable() {
        val event = BridgeApiParser.parseEvent(JSONObject("""{"event_type":"connected","status":"ok"}"""))

        assertEquals("connected", event.eventType)
        assertEquals("", event.messageId)
        assertNull(event.text)
    }
}
