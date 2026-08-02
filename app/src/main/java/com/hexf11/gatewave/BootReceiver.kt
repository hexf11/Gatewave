package com.hexf11.gatewave

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.content.ContextCompat

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val systemBoot = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        val debugBootProbe =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                intent.action == ACTION_DEBUG_BOOT_PROBE
        if (!systemBoot && !debugBootProbe) {
            return
        }
        if (!ProxySettingsStore.load(context).startOnBoot) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_START),
            )
            ProxySettingsStore.setBootStartPending(context, false)
            ProxySettingsStore.recordBootAttempt(context, intent.action.orEmpty(), "STARTED")
        } catch (error: RuntimeException) {
            // Custom test broadcasts don't inherit the system BOOT_COMPLETED FGS exemption.
            // Keep a recovery marker instead of crashing; the next visible app launch consumes it.
            ProxySettingsStore.setBootStartPending(context, true)
            ProxySettingsStore.recordBootAttempt(
                context,
                intent.action.orEmpty(),
                "DEFERRED:${error.javaClass.simpleName}",
            )
            Log.w(TAG, "Boot start deferred: ${error.message}")
        }
    }

    companion object {
        const val ACTION_DEBUG_BOOT_PROBE = "com.hexf11.gatewave.DEBUG_BOOT_PROBE"
        private const val TAG = "GatewaveBoot"
    }
}
