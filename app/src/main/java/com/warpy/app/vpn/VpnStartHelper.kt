package com.warpy.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.warpy.app.data.SettingsStore
import com.warpy.app.model.AppTunnelMode

internal sealed interface VpnPermissionState {
    data object Granted : VpnPermissionState
    data class Required(val intent: Intent) : VpnPermissionState
    data class Failed(val message: String) : VpnPermissionState
}

internal object VpnStartHelper {
    fun checkPermission(context: Context): VpnPermissionState =
        try {
            VpnService.prepare(context)?.let(VpnPermissionState::Required)
                ?: VpnPermissionState.Granted
        } catch (error: RuntimeException) {
            val failure = classifyVpnLaunchFailure(error)
            VpnPermissionState.Failed(failure.message)
        }

    fun start(
        context: Context,
        config: String,
        stabilityModeEnabled: Boolean,
        forceRestart: Boolean = false,
    ): VpnLaunchResult {
        if (config.isBlank()) return VpnLaunchResult.MissingConfiguration
        return when (val permission = checkPermission(context)) {
            VpnPermissionState.Granted -> launch(
                context = context,
                intent = Intent(context, WarpyService::class.java)
                    .putExtra(WarpyService.EXTRA_CONFIG, config)
                    .putExtra(WarpyService.EXTRA_STABILITY_MODE, stabilityModeEnabled)
                    .putExtra(WarpyService.EXTRA_FORCE_RESTART, forceRestart),
            )
            is VpnPermissionState.Required -> VpnLaunchResult.PermissionRequired
            is VpnPermissionState.Failed -> VpnLaunchResult.Failed(permission.message)
        }
    }

    fun startFromSavedState(context: Context): VpnLaunchResult {
        when (val permission = checkPermission(context)) {
            VpnPermissionState.Granted -> Unit
            is VpnPermissionState.Required -> return VpnLaunchResult.PermissionRequired
            is VpnPermissionState.Failed -> return VpnLaunchResult.Failed(permission.message)
        }

        val serviceIntent = Intent(context, WarpyService::class.java)
        val settings = runCatching { SettingsStore(context.applicationContext).load() }
            .getOrElse { return VpnLaunchResult.Failed("Не удалось прочитать настройки VPN") }
        val config = if (settings.profile != null &&
            (settings.appTunnelMode != AppTunnelMode.Include || settings.tunneledApps.isNotEmpty()) &&
            (settings.siteTunnelMode != AppTunnelMode.Include || settings.tunneledSites.isNotEmpty())
        ) {
            runCatching { SingBoxConfigBuilder.build(settings) }.getOrDefault("")
        } else {
            ""
        }

        if (config.isNotBlank()) {
            return launch(
                context = context,
                intent = serviceIntent
                    .putExtra(WarpyService.EXTRA_CONFIG, config)
                    .putExtra(WarpyService.EXTRA_STABILITY_MODE, settings.stabilityModeEnabled),
            )
        } else if (WarpyService.hasSavedConfig(context)) {
            return launch(context, serviceIntent)
        } else {
            return VpnLaunchResult.MissingConfiguration
        }
    }

    private fun launch(context: Context, intent: Intent): VpnLaunchResult =
        executeVpnServiceLaunch(
            launch = { ContextCompat.startForegroundService(context, intent) },
        )
}
