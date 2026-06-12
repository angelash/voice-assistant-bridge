package com.audiobridge.client.phoneagent.policy

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

object AndroidPhoneAgentDeviceState {
    fun read(context: Context): PhoneAgentDeviceState {
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = connectivity?.activeNetwork
        val caps = active?.let { connectivity.getNetworkCapabilities(it) }
        val connected = caps != null && (
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            )
        val metered = if (connected) {
            connectivity?.isActiveNetworkMetered ?: true
        } else {
            false
        }
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return PhoneAgentDeviceState(
            networkConnected = connected,
            networkMetered = metered,
            charging = charging,
        )
    }
}
