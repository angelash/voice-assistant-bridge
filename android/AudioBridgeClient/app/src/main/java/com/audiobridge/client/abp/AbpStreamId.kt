package com.audiobridge.client.abp

enum class AbpStreamId(val id: Int) {
    DOWNLINK(1),
    UPLINK(2);

    companion object {
        fun fromId(id: Int): AbpStreamId? = entries.firstOrNull { it.id == id }
    }
}

