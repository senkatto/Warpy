package com.warpy.app.vpn

internal data class UpstreamIdentity(
    val networkHandle: Long,
    val interfaceName: String?,
    val dnsServers: List<String> = emptyList(),
    val isMetered: Boolean? = null,
)

internal fun isUsablePhysicalNetwork(
    hasInternet: Boolean,
    isValidated: Boolean,
    isSuspended: Boolean,
    isVpn: Boolean,
    isBlocked: Boolean,
): Boolean =
    isHandoverCandidatePhysicalNetwork(
        hasInternet = hasInternet,
        isSuspended = isSuspended,
        isVpn = isVpn,
        isBlocked = isBlocked,
    ) && isValidated

internal fun isHandoverCandidatePhysicalNetwork(
    hasInternet: Boolean,
    isSuspended: Boolean,
    isVpn: Boolean,
    isBlocked: Boolean,
): Boolean = hasInternet && !isSuspended && !isVpn && !isBlocked

internal fun physicalNetworkPriority(
    isValidated: Boolean,
    hasEthernet: Boolean,
    hasWifi: Boolean,
    hasCellular: Boolean,
    isMetered: Boolean,
    isCurrent: Boolean,
): Int {
    val transportPriority = when {
        hasEthernet -> 300
        hasWifi -> 200
        hasCellular -> 100
        else -> 0
    }
    return (if (isValidated) 1_000 else 0) +
        transportPriority +
        (if (isMetered) 0 else 20) +
        (if (isCurrent) 5 else 0)
}

internal fun shouldResetConnectionsAfterSleep(screenOffMillis: Long): Boolean =
    screenOffMillis >= STALE_CONNECTION_RESET_THRESHOLD_MS

internal fun shouldRetryCommandHandshake(failedAttempts: Int, elapsedMillis: Long): Boolean =
    failedAttempts < MAX_COMMAND_HANDSHAKE_ATTEMPTS && elapsedMillis < COMMAND_HANDSHAKE_TIMEOUT_MS

internal const val NETWORK_CHANGE_DEBOUNCE_MS = 350L
internal const val MAX_COMMAND_HANDSHAKE_ATTEMPTS = 20
internal const val COMMAND_HANDSHAKE_RETRY_DELAY_MS = 100L
private const val COMMAND_HANDSHAKE_TIMEOUT_MS = 5_000L
private const val STALE_CONNECTION_RESET_THRESHOLD_MS = 30_000L
