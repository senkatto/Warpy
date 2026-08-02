package com.warpy.app.vpn.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class VpnSessionController(
    initialSnapshot: VpnSessionSnapshot,
    private val reducer: VpnSessionReducer,
    scope: CoroutineScope,
    private val onSnapshotChanged: (VpnSessionSnapshot) -> Unit = {},
    private val onEffects: (List<VpnSessionEffect>) -> Unit = {},
) {
    private data class PendingEvent(
        val event: VpnSessionEvent,
        val result: CompletableDeferred<VpnSessionReduction>,
    )

    private val events = Channel<PendingEvent>(Channel.UNLIMITED)

    @Volatile
    private var currentSnapshot = initialSnapshot

    private val actor: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (pending in events) {
            val previous = currentSnapshot
            val reduction = reducer.reduce(previous, pending.event)
            currentSnapshot = reduction.snapshot

            if (reduction.snapshot != previous) {
                onSnapshotChanged(reduction.snapshot)
            }
            if (reduction.effects.isNotEmpty()) {
                onEffects(reduction.effects)
            }
            pending.result.complete(reduction)
        }
    }

    fun snapshot(): VpnSessionSnapshot = currentSnapshot

    suspend fun dispatch(event: VpnSessionEvent): VpnSessionReduction {
        val result = CompletableDeferred<VpnSessionReduction>()
        events.send(PendingEvent(event, result))
        return result.await()
    }

    fun close() {
        events.close()
        actor.cancel()
    }
}
