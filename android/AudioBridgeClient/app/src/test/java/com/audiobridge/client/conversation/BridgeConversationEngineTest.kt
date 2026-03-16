package com.audiobridge.client.conversation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class BridgeConversationEngineTest {
    private class FakeTransport : ConversationTransport {
        var submitResponse: JSONObject = JSONObject("""{"ok":true}""")
        var summaryResponse: JSONObject = JSONObject("""{"ok":true,"summary":"ok"}""")
        val pollResponses = ArrayDeque<JSONObject>()

        override fun postJson(baseUrl: String, path: String, body: JSONObject): JSONObject {
            return when (path) {
                "/v1/messages" -> submitResponse
                "/v1/operator/summarize" -> summaryResponse
                else -> throw IllegalStateException("unexpected path: $path")
            }
        }

        override fun getJson(baseUrl: String, path: String): JSONObject {
            if (pollResponses.isEmpty()) {
                return JSONObject("""{"status":"WAITING_OPENCLAW"}""")
            }
            return pollResponses.removeFirst()
        }
    }

    private class EventCollector : BridgeConversationEngine.Listener {
        val states = CopyOnWriteArrayList<ConversationState>()
        val roles = CopyOnWriteArrayList<RoleMessage>()
        val notices = CopyOnWriteArrayList<SystemNotice>()
        val longDecisions = CopyOnWriteArrayList<LongReplyDecisionRequired>()

        override fun onStateChanged(state: ConversationState) {
            states.add(state)
        }

        override fun onRoleMessage(message: RoleMessage) {
            roles.add(message)
        }

        override fun onSystemNotice(notice: SystemNotice) {
            notices.add(notice)
        }

        override fun onLongReplyDecisionRequired(request: LongReplyDecisionRequired) {
            longDecisions.add(request)
        }
    }

    @Test
    fun submitTextSync_emitsStatesAndRoleMessages() {
        val transport = FakeTransport()
        transport.submitResponse = JSONObject(
            """
            {
              "ok": true,
              "status": "WAITING_OPENCLAW",
              "message_id": "m-1",
              "local_reply": "queued",
              "local_source_label": "Local Operator"
            }
            """.trimIndent()
        )
        transport.pollResponses.add(
            JSONObject(
                """
                {
                  "status": "DELIVERED",
                  "messages": [
                    {"source":"local-operator","source_label":"Local Operator","kind":"quick_reply","text":"queued"},
                    {"source":"openclaw","source_label":"OpenClaw","kind":"final_reply","text":"short reply"}
                  ]
                }
                """.trimIndent()
            )
        )

        val engine = BridgeConversationEngine(transport = transport)
        val collector = EventCollector()
        engine.addListener(collector)

        engine.submitTextSync(
            ConversationSubmitRequest(
                text = "hello",
                sessionId = "s1",
                clientId = "c1",
                endpoint = BridgeEndpointInfo("LAN", "http://127.0.0.1:8765", "wifi"),
                timeoutSec = 1,
                intervalMs = 1,
            )
        )

        assertTrue(collector.states.contains(ConversationState.SENDING))
        assertTrue(collector.states.contains(ConversationState.WAITING_OPENCLAW))
        assertTrue(collector.states.contains(ConversationState.DELIVERED))
        assertEquals(2, collector.roles.size)
        assertEquals(RoleSource.LOCAL_OPERATOR, collector.roles[0].source)
        assertEquals(RoleSource.OPENCLAW, collector.roles[1].source)
        assertFalse(collector.roles[1].requiresLongDecision)
        assertTrue(collector.notices.isEmpty())
    }

    @Test
    fun submitTextSync_timeoutEmitsFailedNotice() {
        val transport = FakeTransport()
        transport.submitResponse = JSONObject(
            """
            {
              "ok": true,
              "status": "WAITING_OPENCLAW",
              "message_id": "m-timeout",
              "local_reply": "queued"
            }
            """.trimIndent()
        )

        val engine = BridgeConversationEngine(transport = transport)
        val collector = EventCollector()
        engine.addListener(collector)

        engine.submitTextSync(
            ConversationSubmitRequest(
                text = "hello",
                sessionId = "s1",
                clientId = "c1",
                endpoint = BridgeEndpointInfo("TUNNEL", "http://x", null),
                timeoutSec = 0,
                intervalMs = 1,
            )
        )

        assertTrue(collector.states.contains(ConversationState.FAILED))
        assertTrue(collector.notices.any { it.text.contains("timeout waiting final reply") })
    }

    @Test
    fun submitTextSync_longOpenClawReplyEmitsDecisionEvent() {
        val transport = FakeTransport()
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("source", "openclaw")
                .put("source_label", "OpenClaw")
                .put("kind", "final_reply")
                .put("text", "This is a very long reply message that should trigger long-reply decision flow.")
        )
        transport.submitResponse = JSONObject()
            .put("ok", true)
            .put("status", "DELIVERED")
            .put("messages", messages)

        val engine = BridgeConversationEngine(transport = transport)
        val collector = EventCollector()
        engine.addListener(collector)

        engine.submitTextSync(
            ConversationSubmitRequest(
                text = "hello",
                sessionId = "s1",
                clientId = "c1",
                endpoint = BridgeEndpointInfo("LAN", "http://x", "wifi"),
            )
        )

        assertEquals(1, collector.roles.size)
        assertTrue(collector.roles[0].requiresLongDecision)
        assertEquals(1, collector.longDecisions.size)
        assertEquals("s1", collector.longDecisions[0].sessionId)
    }

    @Test
    fun requestLocalSummary_returnsSummaryText() {
        val transport = FakeTransport()
        transport.summaryResponse = JSONObject("""{"ok":true,"summary":"brief summary"}""")
        val engine = BridgeConversationEngine(transport = transport)
        val request = LongReplyDecisionRequired(
            id = "id",
            endpoint = BridgeEndpointInfo("LAN", "http://x", "wifi"),
            sessionId = "s1",
            clientId = "c1",
            originalRaw = "original",
            originalDisplay = "original",
            deadlineAtMs = System.currentTimeMillis() + 1000L,
        )

        val summary = engine.requestLocalSummary(request, maxChars = 90)
        assertEquals("brief summary", summary)
    }
}
