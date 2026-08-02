package com.warpy.app

import com.warpy.app.vpn.session.ElapsedClock
import com.warpy.app.vpn.session.VpnSessionController
import com.warpy.app.vpn.session.VpnSessionEffect
import com.warpy.app.vpn.session.VpnSessionEvent
import com.warpy.app.vpn.session.VpnSessionReducer
import com.warpy.app.vpn.session.VpnSessionSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VpnSessionControllerTest {
    @Test
    fun `concurrent intents are reduced into one ordered generation stream`() = runBlocking {
        val controller = newController(this)
        val started = controller.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        val connected = connect(controller, started.snapshot.generation, "profile_0")

        val generations = (1..50)
            .map { index ->
                async {
                    controller.dispatch(VpnSessionEvent.SwitchRequested("profile_$index"))
                        .snapshot
                        .generation
                }
            }
            .awaitAll()

        assertEquals((2L..51L).toSet(), generations.toSet())
        assertEquals(51L, controller.snapshot().generation)
        assertEquals(connected.connectedAtElapsedMillis, controller.snapshot().connectedAtElapsedMillis)
        controller.close()
    }

    @Test
    fun `snapshot is published before each effect exactly once`() = runBlocking {
        val snapshots = mutableListOf<VpnSessionSnapshot>()
        val effects = mutableListOf<VpnSessionEffect>()
        lateinit var controller: VpnSessionController
        controller = newController(
            scope = this,
            onSnapshotChanged = snapshots::add,
            onEffects = { emittedEffects ->
                assertEquals(snapshots.last(), controller.snapshot())
                effects += emittedEffects
            },
        )

        val reduction = controller.dispatch(VpnSessionEvent.StartRequested("profile_7"))

        assertEquals(listOf(reduction.snapshot), snapshots)
        assertEquals(reduction.effects, effects)
        controller.close()
    }

    @Test
    fun `stale callback cannot overwrite the current snapshot`() = runBlocking {
        val controller = newController(this)
        val first = controller.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        val second = controller.dispatch(VpnSessionEvent.StartRequested("profile_1"))
        val stale = controller.dispatch(
            VpnSessionEvent.ValidationSucceeded(first.snapshot.generation, "profile_0"),
        )

        assertEquals(second.snapshot, stale.snapshot)
        assertEquals(second.snapshot, controller.snapshot())
        assertTrue(stale.effects.isEmpty())
        controller.close()
    }

    private suspend fun connect(
        controller: VpnSessionController,
        generation: Long,
        profileTag: String,
    ): VpnSessionSnapshot {
        controller.dispatch(VpnSessionEvent.CoreStarted(generation))
        controller.dispatch(VpnSessionEvent.TunnelEstablished(generation))
        return controller.dispatch(
            VpnSessionEvent.ValidationSucceeded(generation, profileTag),
        ).snapshot
    }

    private fun newController(
        scope: kotlinx.coroutines.CoroutineScope,
        onSnapshotChanged: (VpnSessionSnapshot) -> Unit = {},
        onEffects: (List<VpnSessionEffect>) -> Unit = {},
    ): VpnSessionController = VpnSessionController(
        initialSnapshot = VpnSessionSnapshot(),
        reducer = VpnSessionReducer(ElapsedClock { 5_000L }),
        scope = scope,
        onSnapshotChanged = onSnapshotChanged,
        onEffects = onEffects,
    )
}
