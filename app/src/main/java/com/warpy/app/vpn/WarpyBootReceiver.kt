package com.warpy.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.warpy.app.data.SettingsStore

class WarpyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!WarpyService.shouldBeRunning(context)) return

        val isPackageUpdated = action == Intent.ACTION_MY_PACKAGE_REPLACED
        val shouldRestore = isPackageUpdated || runCatching {
            SettingsStore(context.applicationContext).load().autoStartOnBoot
        }.getOrDefault(false)
        if (shouldRestore) {
            val result = VpnStartHelper.startFromSavedState(context)
            if (result !is VpnLaunchResult.Started) {
                Log.w(TAG, "Automatic VPN restore deferred: ${result.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "WarpyBootReceiver"
    }
}
