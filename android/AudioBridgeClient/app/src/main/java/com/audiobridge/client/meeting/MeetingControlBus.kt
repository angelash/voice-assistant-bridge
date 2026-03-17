package com.audiobridge.client.meeting

object MeetingControlBus {
    interface Delegate {
        fun onMeetingToggleRequested(enabled: Boolean)
        fun onMeetingRefreshRequested()
    }

    private val lock = Any()
    private var delegate: Delegate? = null

    fun bind(delegate: Delegate) {
        synchronized(lock) {
            this.delegate = delegate
        }
    }

    fun unbind(delegate: Delegate) {
        synchronized(lock) {
            if (this.delegate === delegate) {
                this.delegate = null
            }
        }
    }

    fun requestToggle(enabled: Boolean): Boolean {
        val target = synchronized(lock) { delegate }
        target?.onMeetingToggleRequested(enabled)
        return target != null
    }

    fun requestRefreshStatus(): Boolean {
        val target = synchronized(lock) { delegate }
        target?.onMeetingRefreshRequested()
        return target != null
    }

    fun isBound(): Boolean {
        return synchronized(lock) { delegate != null }
    }
}
