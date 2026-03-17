package com.audiobridge.client.conversation

data class ConversationUiSnapshot(
    val state: ConversationState,
    val localLines: List<String>,
    val openclawLines: List<String>,
)

object ConversationUiState {
    const val VIEW_MAIN = "main"
    const val VIEW_VISUAL = "visual"

    private const val ROLE_HISTORY_MAX = 60
    private val lock = Any()

    @Volatile
    private var activeViewId: String = VIEW_MAIN

    @Volatile
    private var currentState: ConversationState = ConversationState.IDLE

    private val localLines = ArrayDeque<String>()
    private val openclawLines = ArrayDeque<String>()

    fun setActiveView(viewId: String) {
        activeViewId = viewId
    }

    fun isActiveView(viewId: String): Boolean {
        return activeViewId == viewId
    }

    fun updateState(state: ConversationState) {
        currentState = state
    }

    fun pushRoleMessage(message: RoleMessage) {
        val line = message.textDisplay.trim()
        if (line.isBlank()) return
        synchronized(lock) {
            val queue = when (message.source) {
                RoleSource.LOCAL_OPERATOR -> localLines
                RoleSource.OPENCLAW -> openclawLines
            }
            if (queue.isNotEmpty() && queue.last() == line) return
            queue.addLast(line)
            while (queue.size > ROLE_HISTORY_MAX) {
                queue.removeFirst()
            }
        }
    }

    fun snapshot(): ConversationUiSnapshot {
        synchronized(lock) {
            return ConversationUiSnapshot(
                state = currentState,
                localLines = localLines.toList(),
                openclawLines = openclawLines.toList(),
            )
        }
    }
}

