package com.warpy.app.vpn

import kotlin.math.roundToLong

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

internal fun recoveryDelayMillis(
    failedAttempt: Int,
    jitterUnit: Double,
): Long {
    if (failedAttempt <= 0) return 0L
    val exponent = (failedAttempt - 1).coerceIn(0, MAX_RECOVERY_BACKOFF_EXPONENT)
    val baseDelay = (RECOVERY_INITIAL_DELAY_MS shl exponent)
        .coerceAtMost(RECOVERY_MAX_DELAY_MS)
    val normalizedJitter = jitterUnit.coerceIn(0.0, 1.0)
    val jitter = (normalizedJitter * 2.0 - 1.0) * RECOVERY_JITTER_RATIO
    return (baseDelay * (1.0 + jitter)).roundToLong()
}

internal fun shouldContinueRecovery(failedAttempts: Int, elapsedMillis: Long): Boolean =
    failedAttempts < MAX_RECOVERY_ATTEMPTS && elapsedMillis < RECOVERY_TIME_BUDGET_MS

internal fun shouldResetConnectionsAfterSleep(screenOffMillis: Long): Boolean =
    screenOffMillis >= STALE_CONNECTION_RESET_THRESHOLD_MS

internal fun shouldRetryCommandHandshake(failedAttempts: Int, elapsedMillis: Long): Boolean =
    failedAttempts < MAX_COMMAND_HANDSHAKE_ATTEMPTS && elapsedMillis < COMMAND_HANDSHAKE_TIMEOUT_MS

internal const val MAX_RECOVERY_ATTEMPTS = 6
internal const val RECOVERY_TIME_BUDGET_MS = 90_000L
internal const val NETWORK_CHANGE_DEBOUNCE_MS = 350L
internal const val MAX_COMMAND_HANDSHAKE_ATTEMPTS = 20
internal const val COMMAND_HANDSHAKE_RETRY_DELAY_MS = 100L
private const val RECOVERY_INITIAL_DELAY_MS = 1_000L
private const val RECOVERY_MAX_DELAY_MS = 20_000L
private const val RECOVERY_JITTER_RATIO = 0.20
private const val MAX_RECOVERY_BACKOFF_EXPONENT = 5
private const val COMMAND_HANDSHAKE_TIMEOUT_MS = 5_000L
private const val STALE_CONNECTION_RESET_THRESHOLD_MS = 30_000L
