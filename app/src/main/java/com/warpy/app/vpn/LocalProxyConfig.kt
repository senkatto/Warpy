package com.warpy.app.vpn

import java.net.BindException

data class LocalProxyConfig(
    val port: Int,
    val username: String,
    val password: String,
)

internal object LocalProxyBindPolicy {
    private const val ADDRESS_IN_USE = "address already in use"

    fun isAddressInUse(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any { cause ->
            cause is BindException ||
                cause.message?.contains(ADDRESS_IN_USE, ignoreCase = true) == true
        }
}

internal object LocalProxyStartupRetrier {
    fun <T> start(
        maxAttempts: Int,
        allocateProxy: () -> LocalProxyConfig,
        onBindConflict: (attempt: Int) -> Unit = {},
        startAttempt: (LocalProxyConfig) -> T,
    ): T {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return startAttempt(allocateProxy())
            } catch (error: Exception) {
                lastError = error
                if (!LocalProxyBindPolicy.isAddressInUse(error) || attempt == maxAttempts - 1) {
                    throw error
                }
                onBindConflict(attempt + 1)
            }
        }
        throw lastError ?: IllegalStateException("Local proxy did not start")
    }
}
