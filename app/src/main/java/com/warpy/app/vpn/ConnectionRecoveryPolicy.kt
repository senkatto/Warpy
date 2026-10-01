package com.warpy.app.vpn

internal data class UpstreamIdentity(
    val networkHandle: Long,
    val interfaceName: String?,
    val dnsServers: List<String> = emptyList(),
    val isMetered: Boolean? = null,
)

internal fun UpstreamIdentity.hasSameConnection(other: UpstreamIdentity?): Boolean =
    other != null && networkHandle == other.networkHandle && interfaceName == other.interfaceName

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
    isSystemPreferred: Boolean = false,
): Int {
    val transportPriority = when {
        hasEthernet -> 300
        hasWifi -> 200
        hasCellular -> 100
        else -> 0
    }
    return (if (isValidated) 1_000 else 0) +
        (if (isSystemPreferred) 500 else 0) +
        transportPriority +
        (if (isMetered) 0 else 20) +
        (if (isCurrent) 5 else 0)
}

internal fun shouldRetryCommandHandshake(failedAttempts: Int, elapsedMillis: Long): Boolean =
    failedAttempts < MAX_COMMAND_HANDSHAKE_ATTEMPTS && elapsedMillis < COMMAND_HANDSHAKE_TIMEOUT_MS

internal fun tunnelWatchdogIntervalMillis(isInteractive: Boolean, consecutiveFailures: Int): Long =
    if (isInteractive || consecutiveFailures > 0) 30_000L else 5 * 60_000L

internal const val NETWORK_CHANGE_DEBOUNCE_MS = 350L
internal const val MAX_COMMAND_HANDSHAKE_ATTEMPTS = 20
internal const val COMMAND_HANDSHAKE_RETRY_DELAY_MS = 100L
private const val COMMAND_HANDSHAKE_TIMEOUT_MS = 5_000L
