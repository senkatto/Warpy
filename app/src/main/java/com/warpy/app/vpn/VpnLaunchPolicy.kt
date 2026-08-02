package com.warpy.app.vpn

internal sealed interface VpnLaunchResult {
    data object Started : VpnLaunchResult
    data object PermissionRequired : VpnLaunchResult
    data object MissingConfiguration : VpnLaunchResult
    data class Failed(val message: String) : VpnLaunchResult
}

internal fun executeVpnServiceLaunch(
    launch: () -> Unit,
    isBackgroundStartRestricted: (RuntimeException) -> Boolean = {
        it.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    },
): VpnLaunchResult =
    try {
        launch()
        VpnLaunchResult.Started
    } catch (error: RuntimeException) {
        classifyVpnLaunchFailure(error, isBackgroundStartRestricted)
    }

internal fun classifyVpnLaunchFailure(
    error: RuntimeException,
    isBackgroundStartRestricted: (RuntimeException) -> Boolean = {
        it.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    },
): VpnLaunchResult.Failed {
    val message = when {
        error is SecurityException ->
            "Android запретил запуск VPN. Проверьте разрешение VPN в настройках системы"
        isBackgroundStartRestricted(error) ->
            "Android не разрешил запустить VPN в фоне. Откройте Warpy и повторите"
        else -> "Не удалось запустить VPN"
    }
    return VpnLaunchResult.Failed(message)
}
