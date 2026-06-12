package com.audiobridge.client.phoneagent.policy

import com.audiobridge.client.phoneagent.model.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentPolicyTest {
    @Test
    fun networkUseRejectsMeteredNetworkWhenMobileSyncDisabled() {
        val settings = AppSettings(allowMobileNetworkSync = false)
        val state = PhoneAgentDeviceState(
            networkConnected = true,
            networkMetered = true,
            charging = true,
        )

        val decision = PhoneAgentPolicy.networkUse(settings, state)

        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("计量网络"))
    }

    @Test
    fun networkUseAllowsUnmeteredNetworkWhenMobileSyncDisabled() {
        val settings = AppSettings(allowMobileNetworkSync = false)
        val state = PhoneAgentDeviceState(
            networkConnected = true,
            networkMetered = false,
            charging = false,
        )

        val decision = PhoneAgentPolicy.networkUse(settings, state)

        assertTrue(decision.allowed)
    }

    @Test
    fun captureStartRejectsBatteryUseByDefault() {
        val settings = AppSettings(
            allowAutoCapture = true,
            allowMobileNetworkSync = true,
            allowCaptureOnBattery = false,
        )
        val state = PhoneAgentDeviceState(
            networkConnected = true,
            networkMetered = false,
            charging = false,
        )

        val decision = PhoneAgentPolicy.captureStart(settings, state)

        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("未充电"))
    }

    @Test
    fun captureStartAllowsChargingUnmeteredNetwork() {
        val settings = AppSettings(
            allowAutoCapture = true,
            allowMobileNetworkSync = false,
            allowCaptureOnBattery = false,
        )
        val state = PhoneAgentDeviceState(
            networkConnected = true,
            networkMetered = false,
            charging = true,
        )

        val decision = PhoneAgentPolicy.captureStart(settings, state)

        assertTrue(decision.allowed)
    }
}
