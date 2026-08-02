package com.warpy.app.vpn

import android.content.Context
import android.content.Intent
import com.warpy.app.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Serializes commands sent to [WarpyService]. The service remains the only
 * authority for connection state; this class never predicts a final status.
 */
internal class VpnCommandCoordinator(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var pendingRestart: Job? = null

    fun start(settings: AppSettings, config: String, forceRestart: Boolean = false): VpnLaunchResult {
        pendingRestart?.cancel()
        pendingRestart = null
        return launch(settings, config, forceRestart)
    }

    fun stop() {
        pendingRestart?.cancel()
        pendingRestart = null
        appContext.startService(
            Intent(appContext, WarpyService::class.java).setAction(WarpyService.ACTION_STOP),
        )
    }

    fun restartNowIfRunning(
        settings: AppSettings,
        config: String,
    ): VpnLaunchResult? {
        pendingRestart?.cancel()
        pendingRestart = null
        return if (WarpyService.shouldBeRunning(appContext)) {
            launch(settings, config, forceRestart = true)
        } else {
            null
        }
    }

    fun scheduleRestartIfRunning(
        settings: () -> AppSettings,
        config: () -> String,
        onResult: (VpnLaunchResult) -> Unit,
    ) {
        if (!WarpyService.shouldBeRunning(appContext)) return
        pendingRestart?.cancel()
        pendingRestart = scope.launch {
            delay(RESTART_DEBOUNCE_MS)
            if (WarpyService.shouldBeRunning(appContext)) {
                onResult(launch(settings(), config(), forceRestart = true))
            }
            pendingRestart = null
        }
    }

    fun close() {
        pendingRestart?.cancel()
        pendingRestart = null
    }

    private fun launch(
        settings: AppSettings,
        config: String,
        forceRestart: Boolean,
    ): VpnLaunchResult = VpnStartHelper.start(
        context = appContext,
        config = config,
        stabilityModeEnabled = settings.stabilityModeEnabled,
        forceRestart = forceRestart,
    )

    private companion object {
        const val RESTART_DEBOUNCE_MS = 800L
    }
}
