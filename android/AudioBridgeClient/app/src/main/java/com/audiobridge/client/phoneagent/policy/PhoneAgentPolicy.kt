package com.audiobridge.client.phoneagent.policy

import com.audiobridge.client.phoneagent.model.AppSettings

data class PhoneAgentDeviceState(
    val networkConnected: Boolean,
    val networkMetered: Boolean,
    val charging: Boolean,
)

data class PhoneAgentPolicyDecision(
    val allowed: Boolean,
    val reason: String = "",
)

object PhoneAgentPolicy {
    fun networkUse(settings: AppSettings, state: PhoneAgentDeviceState): PhoneAgentPolicyDecision {
        if (!state.networkConnected) {
            return PhoneAgentPolicyDecision(false, "当前没有可用网络，消息已保留在离线队列。")
        }
        if (!settings.allowMobileNetworkSync && state.networkMetered) {
            return PhoneAgentPolicyDecision(false, "当前是移动/计量网络，设置未允许同步，已保留在离线队列。")
        }
        return PhoneAgentPolicyDecision(true)
    }

    fun captureStart(settings: AppSettings, state: PhoneAgentDeviceState): PhoneAgentPolicyDecision {
        if (!settings.allowAutoCapture) {
            return PhoneAgentPolicyDecision(false, "设置未开启“允许手动启动前台持续采集”，不会启动摄像头持续采集。")
        }
        val network = networkUse(settings, state)
        if (!network.allowed) return network
        if (!settings.allowCaptureOnBattery && !state.charging) {
            return PhoneAgentPolicyDecision(false, "当前未充电，设置未允许电池供电持续采集。")
        }
        return PhoneAgentPolicyDecision(true)
    }
}
