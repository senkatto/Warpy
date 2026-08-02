package com.warpy.app.vpn.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal enum class CoreCommand {
    OutboundGroup,
    Connections,
}

internal interface CoreCommandClient : AutoCloseable {
    fun connect()
    fun selectOutbound(groupTag: String, outboundTag: String)
    fun closeConnections()
}

internal fun interface CoreCommandClientFactory {
    fun create(command: CoreCommand): CoreCommandClient
}

internal data class CoreOperationResult(
    val succeeded: Boolean,
    val attempts: Int,
    val error: Throwable? = null,
)

internal interface CoreGateway {
    fun selectOutbound(outboundTag: String): CoreOperationResult
    suspend fun selectOutboundWhenReady(outboundTag: String): CoreOperationResult
    fun closeConnections(): CoreOperationResult
}

internal class DefaultCoreGateway(
    private val clientFactory: CoreCommandClientFactory,
    private val groupTag: String,
    private val clock: ElapsedClock,
    private val shouldRetryHandshake: (failedAttempts: Int, elapsedMillis: Long) -> Boolean,
    private val handshakeRetryDelayMillis: Long,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : CoreGateway {
    override fun selectOutbound(outboundTag: String): CoreOperationResult =
        selectOutboundOnce(outboundTag, attempts = 1)

    override suspend fun selectOutboundWhenReady(outboundTag: String): CoreOperationResult {
        val startedAt = clock.nowMillis()
        var failedAttempts = 0
        var lastError: Throwable? = null
        while (true) {
            val result = selectOutboundOnce(outboundTag, attempts = failedAttempts + 1)
            if (result.succeeded) return result
            failedAttempts = result.attempts
            lastError = result.error
            val elapsedMillis = (clock.nowMillis() - startedAt).coerceAtLeast(0L)
            if (!shouldRetryHandshake(failedAttempts, elapsedMillis)) {
                return CoreOperationResult(
                    succeeded = false,
                    attempts = failedAttempts,
                    error = lastError,
                )
            }
            wait(handshakeRetryDelayMillis)
        }
    }

    override fun closeConnections(): CoreOperationResult {
        var client: CoreCommandClient? = null
        return try {
            client = clientFactory.create(CoreCommand.Connections)
            client.connect()
            client.closeConnections()
            CoreOperationResult(succeeded = true, attempts = 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CoreOperationResult(succeeded = false, attempts = 1, error = error)
        } finally {
            runCatching { client?.close() }
        }
    }

    private fun selectOutboundOnce(
        outboundTag: String,
        attempts: Int,
    ): CoreOperationResult {
        var client: CoreCommandClient? = null
        return try {
            client = clientFactory.create(CoreCommand.OutboundGroup)
            client.connect()
            client.selectOutbound(groupTag, outboundTag)
            CoreOperationResult(succeeded = true, attempts = attempts)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CoreOperationResult(succeeded = false, attempts = attempts, error = error)
        } finally {
            runCatching { client?.close() }
        }
    }
}
