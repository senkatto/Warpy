package com.warpy.app.vpn.session

import com.warpy.app.model.VpnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SessionValidationResult(
    val succeeded: Boolean,
    val message: String? = null,
    val recoverable: Boolean = false,
)

internal sealed interface OutboundSwitchResult {
    data class Succeeded(val runtimeProfileTag: String) : OutboundSwitchResult
    data class RolledBack(val message: String) : OutboundSwitchResult
    data class Failed(val message: String) : OutboundSwitchResult
}

internal sealed interface ConnectionRecoveryResult {
    data class Succeeded(val runtimeProfileTag: String) : ConnectionRecoveryResult
    data class Deferred(val message: String) : ConnectionRecoveryResult
    data class Exhausted(val message: String) : ConnectionRecoveryResult
}

internal interface VpnSessionOperations {
    suspend fun startCore(generation: Long, profileTag: String)
    suspend fun stopCore(coreGeneration: Long)
    suspend fun validateTunnel(
        generation: Long,
        reason: ValidationReason,
    ): SessionValidationResult
    suspend fun switchOutbound(
        generation: Long,
        profileTag: String,
        previousRuntimeProfileTag: String,
    ): OutboundSwitchResult
    suspend fun recoverConnection(
        generation: Long,
        request: RecoveryRequest,
    ): ConnectionRecoveryResult
    fun cancelOperations(generation: Long)
}

internal class VpnSessionRuntime(
    scope: CoroutineScope,
    reducer: VpnSessionReducer,
    initialSnapshot: VpnSessionSnapshot = VpnSessionSnapshot(),
    private val operations: VpnSessionOperations,
    onSnapshotChanged: (VpnSessionSnapshot) -> Unit = {},
) {
    private val runtimeScope = scope
    private val resourceMutex = Mutex()
    private val resourceJobOwner = CancellableJobOwner(scope)
    private val validationJobOwner = CancellableJobOwner(scope)
    private val switchJobOwner = CancellableJobOwner(scope)
    private val recoveryJobOwner = CancellableJobOwner(scope)
    private val controller = VpnSessionController(
        initialSnapshot = initialSnapshot,
        reducer = reducer,
        scope = scope,
        onSnapshotChanged = onSnapshotChanged,
        onEffects = ::handleEffects,
    )

    fun snapshot(): VpnSessionSnapshot = controller.snapshot()

    suspend fun dispatch(event: VpnSessionEvent): VpnSessionReduction =
        controller.dispatch(event)

    fun close() {
        resourceJobOwner.cancel()
        validationJobOwner.cancel()
        switchJobOwner.cancel()
        recoveryJobOwner.cancel()
        controller.close()
    }

    private fun handleEffects(effects: List<VpnSessionEffect>) {
        effects.filterIsInstance<VpnSessionEffect.CancelOperations>().forEach { effect ->
            resourceJobOwner.cancel()
            validationJobOwner.cancel()
            switchJobOwner.cancel()
            recoveryJobOwner.cancel()
            operations.cancelOperations(effect.generation)
        }

        val stop = effects.filterIsInstance<VpnSessionEffect.StopCore>().lastOrNull()
        val start = effects.filterIsInstance<VpnSessionEffect.StartCore>().lastOrNull()
        if (stop != null || start != null) {
            launchResourceTransaction(stop, start)
        }

        effects.filterIsInstance<VpnSessionEffect.EstablishTunnel>().forEach { effect ->
            runtimeScope.launch {
                controller.dispatch(VpnSessionEvent.TunnelEstablished(effect.generation))
            }
        }

        effects.filterIsInstance<VpnSessionEffect.ValidateTunnel>().forEach { effect ->
            launchValidation(effect)
        }

        effects.filterIsInstance<VpnSessionEffect.SelectOutbound>().forEach { effect ->
            launchSwitch(effect)
        }

        effects.filterIsInstance<VpnSessionEffect.RecoverConnection>().forEach { effect ->
            launchRecovery(effect)
        }

    }

    private fun launchResourceTransaction(
        stop: VpnSessionEffect.StopCore?,
        start: VpnSessionEffect.StartCore?,
    ) {
        resourceJobOwner.launch {
            try {
                resourceMutex.withLock {
                    if (stop != null) {
                        operations.stopCore(stop.coreGeneration)
                    }
                    if (start != null && isCurrent(start.generation)) {
                        operations.startCore(start.generation, start.profileTag)
                    }
                }

                if (start != null && isCurrent(start.generation)) {
                    controller.dispatch(VpnSessionEvent.CoreStarted(start.generation))
                } else if (stop?.completionGeneration != null) {
                    controller.dispatch(
                        VpnSessionEvent.StopCompleted(stop.completionGeneration),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val generation = start?.generation ?: return@launch
                controller.dispatch(
                    VpnSessionEvent.ValidationFailed(
                        generation = generation,
                        message = error.message ?: "VPN startup failed",
                        recoverable = false,
                    ),
                )
            }
        }
    }

    private fun launchValidation(effect: VpnSessionEffect.ValidateTunnel) {
        validationJobOwner.launch {
            val result = try {
                operations.validateTunnel(effect.generation, effect.reason)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SessionValidationResult(
                    succeeded = false,
                    message = error.message ?: "VPN validation failed",
                )
            }
            if (!isCurrent(effect.generation)) return@launch

            if (result.succeeded) {
                val runtimeProfileTag = snapshot().preferredProfileTag
                    ?: return@launch
                controller.dispatch(
                    VpnSessionEvent.ValidationSucceeded(
                        generation = effect.generation,
                        runtimeProfileTag = runtimeProfileTag,
                    ),
                )
            } else {
                controller.dispatch(
                    VpnSessionEvent.ValidationFailed(
                        generation = effect.generation,
                        message = result.message ?: "VPN validation failed",
                        recoverable = result.recoverable,
                        reason = effect.reason,
                    ),
                )
            }
        }
    }

    private fun launchSwitch(effect: VpnSessionEffect.SelectOutbound) {
        switchJobOwner.launch {
            val result = try {
                operations.switchOutbound(
                    generation = effect.generation,
                    profileTag = effect.profileTag,
                    previousRuntimeProfileTag = effect.previousRuntimeProfileTag,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                OutboundSwitchResult.Failed(
                    error.message ?: "VPN profile switch failed",
                )
            }
            if (!isCurrent(effect.generation)) return@launch

            when (result) {
                is OutboundSwitchResult.Succeeded -> controller.dispatch(
                    VpnSessionEvent.SwitchSucceeded(
                        generation = effect.generation,
                        runtimeProfileTag = result.runtimeProfileTag,
                    ),
                )
                is OutboundSwitchResult.RolledBack -> controller.dispatch(
                    VpnSessionEvent.SwitchFailed(
                        generation = effect.generation,
                        message = result.message,
                    ),
                )
                is OutboundSwitchResult.Failed -> controller.dispatch(
                    VpnSessionEvent.CoreDied(
                        generation = effect.generation,
                        message = result.message,
                    ),
                )
            }
        }
    }

    private fun launchRecovery(effect: VpnSessionEffect.RecoverConnection) {
        recoveryJobOwner.launch {
            val result = try {
                operations.recoverConnection(effect.generation, effect.request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ConnectionRecoveryResult.Deferred(
                    error.message ?: "VPN recovery failed",
                )
            }
            if (!isCurrent(effect.generation)) return@launch

            when (result) {
                is ConnectionRecoveryResult.Succeeded -> controller.dispatch(
                    VpnSessionEvent.RecoverySucceeded(
                        generation = effect.generation,
                        runtimeProfileTag = result.runtimeProfileTag,
                    ),
                )
                is ConnectionRecoveryResult.Deferred -> controller.dispatch(
                    VpnSessionEvent.RecoveryDeferred(
                        generation = effect.generation,
                        message = result.message,
                    ),
                )
                is ConnectionRecoveryResult.Exhausted -> controller.dispatch(
                    VpnSessionEvent.RecoveryExhausted(
                        generation = effect.generation,
                        message = result.message,
                        stop = effect.request.stopOnExhaustion,
                    ),
                )
            }
        }
    }

    private fun isCurrent(generation: Long): Boolean {
        val snapshot = controller.snapshot()
        return snapshot.generation == generation &&
            snapshot.shouldRun &&
            snapshot.state != VpnState.Stopping &&
            snapshot.state != VpnState.Stopped
    }
}
