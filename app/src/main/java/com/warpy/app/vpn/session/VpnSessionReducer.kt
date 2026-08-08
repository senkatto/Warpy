package com.warpy.app.vpn.session

import com.warpy.app.model.VpnState

internal fun interface ElapsedClock {
    fun nowMillis(): Long
}

internal data class VpnSessionSnapshot(
    val generation: Long = 0L,
    val state: VpnState = VpnState.Stopped,
    val shouldRun: Boolean = false,
    val preferredProfileTag: String? = null,
    val runtimeProfileTag: String? = null,
    val connectedAtElapsedMillis: Long = 0L,
    val screenOffAtElapsedMillis: Long? = null,
    val lastError: String? = null,
)

internal enum class ValidationReason {
    Initial,
    Recovery,
    Wake,
}

internal data class RecoveryRequest(
    val reason: String,
    val probeBeforeRefresh: Boolean,
    val resetConnectionsOnSuccess: Boolean,
    val stopOnExhaustion: Boolean = false,
)

internal sealed interface VpnSessionEvent {
    data class StartRequested(val profileTag: String) : VpnSessionEvent
    data class RestartRequested(val profileTag: String) : VpnSessionEvent
    data object StopRequested : VpnSessionEvent
    data class CoreStarted(val generation: Long) : VpnSessionEvent
    data class TunnelEstablished(val generation: Long) : VpnSessionEvent
    data class ValidationSucceeded(
        val generation: Long,
        val runtimeProfileTag: String,
    ) : VpnSessionEvent
    data class ValidationFailed(
        val generation: Long,
        val message: String,
        val recoverable: Boolean,
        val reason: ValidationReason = ValidationReason.Initial,
    ) : VpnSessionEvent
    data class SwitchRequested(val profileTag: String) : VpnSessionEvent
    data class SwitchSucceeded(
        val generation: Long,
        val runtimeProfileTag: String,
    ) : VpnSessionEvent
    data class SwitchFailed(val generation: Long, val message: String) : VpnSessionEvent
    data class RecoveryRequested(
        val generation: Long,
        val request: RecoveryRequest,
    ) : VpnSessionEvent
    data class RecoverySucceeded(
        val generation: Long,
        val runtimeProfileTag: String,
    ) : VpnSessionEvent
    data class RecoveryDeferred(val generation: Long, val message: String) : VpnSessionEvent
    data class RecoveryExhausted(
        val generation: Long,
        val message: String,
        val stop: Boolean,
    ) : VpnSessionEvent
    data class StopCompleted(val generation: Long) : VpnSessionEvent
    data object ScreenLocked : VpnSessionEvent
    data object ScreenUnlocked : VpnSessionEvent
    data class UpstreamChanged(val available: Boolean) : VpnSessionEvent
    data class CoreDied(val generation: Long, val message: String) : VpnSessionEvent
    data object PermissionRevoked : VpnSessionEvent
    data class ProcessRestored(
        val shouldRun: Boolean,
        val profileTag: String?,
    ) : VpnSessionEvent
    data class BootCompleted(
        val autoStart: Boolean,
        val profileTag: String?,
    ) : VpnSessionEvent
}

internal sealed interface VpnSessionEffect {
    data class CancelOperations(val generation: Long) : VpnSessionEffect
    data class StartCore(val generation: Long, val profileTag: String) : VpnSessionEffect
    data class StopCore(
        val coreGeneration: Long,
        val completionGeneration: Long? = null,
    ) : VpnSessionEffect
    data class EstablishTunnel(val generation: Long) : VpnSessionEffect
    data class ValidateTunnel(
        val generation: Long,
        val reason: ValidationReason,
    ) : VpnSessionEffect
    data class SelectOutbound(
        val generation: Long,
        val profileTag: String,
        val previousRuntimeProfileTag: String,
    ) : VpnSessionEffect
    data class RecoverConnection(
        val generation: Long,
        val request: RecoveryRequest,
    ) : VpnSessionEffect
}

internal data class VpnSessionReduction(
    val snapshot: VpnSessionSnapshot,
    val effects: List<VpnSessionEffect> = emptyList(),
)

internal class VpnSessionReducer(
    private val clock: ElapsedClock,
    private val wakeProbeThresholdMillis: Long = 30_000L,
) {
    fun reduce(
        snapshot: VpnSessionSnapshot,
        event: VpnSessionEvent,
    ): VpnSessionReduction = when (event) {
        is VpnSessionEvent.StartRequested -> start(
            snapshot,
            event.profileTag,
            recovering = false,
            forceRestart = false,
        )
        is VpnSessionEvent.RestartRequested -> start(
            snapshot,
            event.profileTag,
            recovering = false,
            forceRestart = true,
        )
        VpnSessionEvent.StopRequested -> stop(snapshot)
        is VpnSessionEvent.CoreStarted -> current(snapshot, event.generation) {
            if (snapshot.state !in setOf(VpnState.Starting, VpnState.Recovering)) {
                return@current VpnSessionReduction(snapshot)
            }
            VpnSessionReduction(
                snapshot,
                listOf(VpnSessionEffect.EstablishTunnel(event.generation)),
            )
        }
        is VpnSessionEvent.TunnelEstablished -> current(snapshot, event.generation) {
            if (snapshot.state !in setOf(VpnState.Starting, VpnState.Recovering)) {
                return@current VpnSessionReduction(snapshot)
            }
            val reason = if (snapshot.state == VpnState.Recovering) {
                ValidationReason.Recovery
            } else {
                ValidationReason.Initial
            }
            VpnSessionReduction(
                snapshot.copy(state = VpnState.Validating),
                listOf(VpnSessionEffect.ValidateTunnel(event.generation, reason)),
            )
        }
        is VpnSessionEvent.ValidationSucceeded -> current(snapshot, event.generation) {
            if (!snapshot.shouldRun) return@current VpnSessionReduction(snapshot)
            connected(
                snapshot = snapshot,
                runtimeProfileTag = event.runtimeProfileTag,
            )
        }
        is VpnSessionEvent.ValidationFailed -> validationFailed(snapshot, event)
        is VpnSessionEvent.SwitchRequested -> switch(snapshot, event.profileTag)
        is VpnSessionEvent.SwitchSucceeded -> current(snapshot, event.generation) {
            if (snapshot.state != VpnState.Validating) {
                return@current VpnSessionReduction(snapshot)
            }
            VpnSessionReduction(
                snapshot.copy(
                    state = VpnState.Connected,
                    preferredProfileTag = event.runtimeProfileTag,
                    runtimeProfileTag = event.runtimeProfileTag,
                    lastError = null,
                ),
            )
        }
        is VpnSessionEvent.SwitchFailed -> current(snapshot, event.generation) {
            if (snapshot.state != VpnState.Validating) {
                return@current VpnSessionReduction(snapshot)
            }
            val previous = snapshot.runtimeProfileTag
                ?: return@current VpnSessionReduction(snapshot)
            VpnSessionReduction(
                snapshot.copy(
                    state = VpnState.Connected,
                    preferredProfileTag = previous,
                    lastError = event.message,
                ),
            )
        }
        is VpnSessionEvent.RecoveryRequested -> current(snapshot, event.generation) {
            if (!snapshot.shouldRun || snapshot.runtimeProfileTag == null) {
                return@current VpnSessionReduction(snapshot)
            }
            VpnSessionReduction(
                snapshot.copy(state = VpnState.Recovering, lastError = null),
                listOf(
                    VpnSessionEffect.RecoverConnection(
                        generation = event.generation,
                        request = event.request,
                    ),
                ),
            )
        }
        is VpnSessionEvent.RecoverySucceeded -> current(snapshot, event.generation) {
            if (!snapshot.shouldRun) return@current VpnSessionReduction(snapshot)
            connected(
                snapshot = snapshot,
                runtimeProfileTag = event.runtimeProfileTag,
            )
        }
        is VpnSessionEvent.RecoveryDeferred -> current(snapshot, event.generation) {
            if (!snapshot.shouldRun) return@current VpnSessionReduction(snapshot)
            VpnSessionReduction(
                snapshot.copy(state = VpnState.Recovering, lastError = event.message),
            )
        }
        is VpnSessionEvent.RecoveryExhausted -> current(snapshot, event.generation) {
            if (!snapshot.shouldRun) return@current VpnSessionReduction(snapshot)
            if (!event.stop) {
                val profileTag = snapshot.preferredProfileTag
                    ?: return@current VpnSessionReduction(
                        snapshot.copy(state = VpnState.Recovering, lastError = event.message),
                    )
                return@current start(
                    snapshot.copy(lastError = event.message),
                    profileTag,
                    recovering = true,
                )
            }
            VpnSessionReduction(
                snapshot.copy(
                    state = VpnState.Error,
                    shouldRun = false,
                    runtimeProfileTag = null,
                    connectedAtElapsedMillis = 0L,
                    lastError = event.message,
                ),
                listOf(VpnSessionEffect.StopCore(event.generation)),
            )
        }
        is VpnSessionEvent.StopCompleted -> current(snapshot, event.generation) {
            if (snapshot.state != VpnState.Stopping) {
                return@current VpnSessionReduction(snapshot)
            }
            VpnSessionReduction(
                snapshot.copy(
                    state = VpnState.Stopped,
                    runtimeProfileTag = null,
                    connectedAtElapsedMillis = 0L,
                    lastError = null,
                ),
            )
        }
        VpnSessionEvent.ScreenLocked -> VpnSessionReduction(
            snapshot.copy(screenOffAtElapsedMillis = clock.nowMillis()),
        )
        VpnSessionEvent.ScreenUnlocked -> screenUnlocked(snapshot)
        is VpnSessionEvent.UpstreamChanged -> upstreamChanged(snapshot, event.available)
        is VpnSessionEvent.CoreDied -> current(snapshot, event.generation) {
            val profileTag = snapshot.preferredProfileTag
            if (!snapshot.shouldRun || profileTag == null) {
                VpnSessionReduction(
                    snapshot.copy(
                        state = VpnState.Error,
                        connectedAtElapsedMillis = 0L,
                        lastError = event.message,
                    ),
                )
            } else {
                start(snapshot, profileTag, recovering = true)
            }
        }
        VpnSessionEvent.PermissionRevoked -> stop(snapshot).let { reduction ->
            reduction.copy(snapshot = reduction.snapshot.copy(lastError = "VPN permission revoked"))
        }
        is VpnSessionEvent.ProcessRestored -> restore(
            snapshot,
            event.shouldRun,
            event.profileTag,
        )
        is VpnSessionEvent.BootCompleted -> restore(
            snapshot,
            event.autoStart,
            event.profileTag,
        )
    }

    private fun start(
        snapshot: VpnSessionSnapshot,
        profileTag: String,
        recovering: Boolean,
        forceRestart: Boolean = false,
    ): VpnSessionReduction {
        if (!forceRestart &&
            !recovering &&
            snapshot.shouldRun &&
            snapshot.preferredProfileTag == profileTag &&
            snapshot.state in setOf(
                VpnState.Starting,
                VpnState.Validating,
                VpnState.Connected,
                VpnState.Recovering,
            )
        ) {
            return VpnSessionReduction(snapshot)
        }

        val previousGeneration = snapshot.generation
        val generation = previousGeneration + 1L
        val effects = buildList {
            if (previousGeneration > 0L && snapshot.state != VpnState.Stopped) {
                add(VpnSessionEffect.CancelOperations(previousGeneration))
                add(VpnSessionEffect.StopCore(previousGeneration))
            }
            add(VpnSessionEffect.StartCore(generation, profileTag))
        }
        return VpnSessionReduction(
            snapshot.copy(
                generation = generation,
                state = if (recovering) VpnState.Recovering else VpnState.Starting,
                shouldRun = true,
                preferredProfileTag = profileTag,
                runtimeProfileTag = null,
                connectedAtElapsedMillis = 0L,
                screenOffAtElapsedMillis = null,
                lastError = null,
            ),
            effects,
        )
    }

    private fun stop(snapshot: VpnSessionSnapshot): VpnSessionReduction {
        if (!snapshot.shouldRun && snapshot.state == VpnState.Stopped) {
            return VpnSessionReduction(snapshot)
        }
        val previousGeneration = snapshot.generation
        val generation = previousGeneration + 1L
        val effects = buildList {
            if (previousGeneration > 0L) {
                add(VpnSessionEffect.CancelOperations(previousGeneration))
                add(
                    VpnSessionEffect.StopCore(
                        coreGeneration = previousGeneration,
                        completionGeneration = generation,
                    ),
                )
            }
        }
        return VpnSessionReduction(
            snapshot.copy(
                generation = generation,
                state = VpnState.Stopping,
                shouldRun = false,
                connectedAtElapsedMillis = 0L,
                screenOffAtElapsedMillis = null,
            ),
            effects,
        )
    }

    private fun switch(
        snapshot: VpnSessionSnapshot,
        profileTag: String,
    ): VpnSessionReduction {
        val previousRuntime = snapshot.runtimeProfileTag
            ?: return VpnSessionReduction(snapshot)
        if (!snapshot.shouldRun) {
            return VpnSessionReduction(snapshot)
        }
        if (profileTag == previousRuntime) {
            if (snapshot.preferredProfileTag == profileTag) {
                return VpnSessionReduction(snapshot)
            }
            val previousGeneration = snapshot.generation
            val generation = previousGeneration + 1L
            return VpnSessionReduction(
                snapshot.copy(
                    generation = generation,
                    preferredProfileTag = profileTag,
                    lastError = null,
                ),
                buildList {
                    if (previousGeneration > 0L) {
                        add(VpnSessionEffect.CancelOperations(previousGeneration))
                    }
                },
            )
        }
        val previousGeneration = snapshot.generation
        val generation = previousGeneration + 1L
        return VpnSessionReduction(
            snapshot.copy(
                generation = generation,
                state = VpnState.Validating,
                preferredProfileTag = profileTag,
                lastError = null,
            ),
            buildList {
                if (previousGeneration > 0L) {
                    add(VpnSessionEffect.CancelOperations(previousGeneration))
                }
                add(
                    VpnSessionEffect.SelectOutbound(
                        generation,
                        profileTag,
                        previousRuntime,
                    ),
                )
            },
        )
    }

    private fun validationFailed(
        snapshot: VpnSessionSnapshot,
        event: VpnSessionEvent.ValidationFailed,
    ): VpnSessionReduction = current(snapshot, event.generation) {
        if (event.recoverable && snapshot.shouldRun) {
            VpnSessionReduction(
                snapshot.copy(state = VpnState.Recovering, lastError = event.message),
                listOf(
                    VpnSessionEffect.RecoverConnection(
                        generation = event.generation,
                        request = RecoveryRequest(
                            reason = event.reason.name.lowercase(),
                            probeBeforeRefresh = false,
                            resetConnectionsOnSuccess = event.reason != ValidationReason.Initial,
                            stopOnExhaustion = event.reason == ValidationReason.Initial,
                        ),
                    ),
                ),
            )
        } else {
            VpnSessionReduction(
                snapshot.copy(
                    state = VpnState.Error,
                    shouldRun = false,
                    runtimeProfileTag = null,
                    connectedAtElapsedMillis = 0L,
                    lastError = event.message,
                ),
                listOf(VpnSessionEffect.StopCore(event.generation)),
            )
        }
    }

    private fun screenUnlocked(snapshot: VpnSessionSnapshot): VpnSessionReduction {
        val screenOffAt = snapshot.screenOffAtElapsedMillis
            ?: return VpnSessionReduction(snapshot)
        val screenOffMillis = (clock.nowMillis() - screenOffAt).coerceAtLeast(0L)
        val updated = snapshot.copy(screenOffAtElapsedMillis = null)
        if (snapshot.state != VpnState.Connected || screenOffMillis < wakeProbeThresholdMillis) {
            return VpnSessionReduction(updated)
        }
        return VpnSessionReduction(
            updated,
            listOf(
                VpnSessionEffect.ValidateTunnel(
                    snapshot.generation,
                    ValidationReason.Wake,
                ),
            ),
        )
    }

    private fun upstreamChanged(
        snapshot: VpnSessionSnapshot,
        available: Boolean,
    ): VpnSessionReduction {
        if (!snapshot.shouldRun) return VpnSessionReduction(snapshot)
        val previousGeneration = snapshot.generation
        val generation = previousGeneration + 1L
        return if (available) {
            VpnSessionReduction(
                snapshot.copy(generation = generation, state = VpnState.Validating),
                buildList {
                    if (previousGeneration > 0L) {
                        add(VpnSessionEffect.CancelOperations(previousGeneration))
                    }
                    add(
                        VpnSessionEffect.ValidateTunnel(
                            generation,
                            ValidationReason.Recovery,
                        ),
                    )
                },
            )
        } else {
            VpnSessionReduction(
                snapshot.copy(
                    generation = generation,
                    state = VpnState.Recovering,
                    lastError = "Waiting for network",
                ),
                buildList {
                    if (previousGeneration > 0L) {
                        add(VpnSessionEffect.CancelOperations(previousGeneration))
                    }
                },
            )
        }
    }

    private fun connected(
        snapshot: VpnSessionSnapshot,
        runtimeProfileTag: String,
    ): VpnSessionReduction {
        val connected = snapshot.copy(
            state = VpnState.Connected,
            runtimeProfileTag = runtimeProfileTag,
            connectedAtElapsedMillis = snapshot.connectedAtElapsedMillis
                .takeIf { it > 0L }
                ?: clock.nowMillis(),
            lastError = null,
        )
        return VpnSessionReduction(snapshot = connected)
    }

    private fun restore(
        snapshot: VpnSessionSnapshot,
        shouldRun: Boolean,
        profileTag: String?,
    ): VpnSessionReduction {
        if (!shouldRun || profileTag == null) {
            return VpnSessionReduction(
                snapshot.copy(
                    state = VpnState.Stopped,
                    shouldRun = false,
                    runtimeProfileTag = null,
                    connectedAtElapsedMillis = 0L,
                ),
            )
        }
        return start(
            snapshot.copy(
                state = VpnState.Stopped,
                shouldRun = false,
                runtimeProfileTag = null,
                connectedAtElapsedMillis = 0L,
            ),
            profileTag,
            recovering = true,
            forceRestart = false,
        )
    }

    private inline fun current(
        snapshot: VpnSessionSnapshot,
        generation: Long,
        block: () -> VpnSessionReduction,
    ): VpnSessionReduction = if (snapshot.generation == generation) {
        block()
    } else {
        VpnSessionReduction(snapshot)
    }
}
