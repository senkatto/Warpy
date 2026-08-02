package com.warpy.app

import com.warpy.app.model.VpnState
import com.warpy.app.vpn.session.ElapsedClock
import com.warpy.app.vpn.session.RecoveryRequest
import com.warpy.app.vpn.session.ValidationReason
import com.warpy.app.vpn.session.VpnSessionEffect
import com.warpy.app.vpn.session.VpnSessionEvent
import com.warpy.app.vpn.session.VpnSessionReducer
import com.warpy.app.vpn.session.VpnSessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VpnSessionReducerTest {
    private val clock = FakeElapsedClock()
    private val reducer = VpnSessionReducer(clock)

    @Test
    fun `rapid duplicate start is idempotent`() {
        val first = reducer.reduce(
            VpnSessionSnapshot(),
            VpnSessionEvent.StartRequested("profile_0"),
        )
        val duplicate = reducer.reduce(
            first.snapshot,
            VpnSessionEvent.StartRequested("profile_0"),
        )

        assertEquals(1L, first.snapshot.generation)
        assertEquals(first.snapshot, duplicate.snapshot)
        assertTrue(duplicate.effects.isEmpty())
        assertEquals(
            VpnSessionEffect.StartCore(1L, "profile_0"),
            first.effects.single(),
        )
    }

    @Test
    fun `forced restart rebuilds an active session with the same profile`() {
        val connected = connectedSession("profile_0")
        val restarted = reducer.reduce(
            connected,
            VpnSessionEvent.RestartRequested("profile_0"),
        )

        assertEquals(connected.generation + 1L, restarted.snapshot.generation)
        assertEquals(VpnState.Starting, restarted.snapshot.state)
        assertTrue(restarted.snapshot.shouldRun)
        assertEquals(
            listOf(
                VpnSessionEffect.CancelOperations(connected.generation),
                VpnSessionEffect.StopCore(connected.generation),
                VpnSessionEffect.StartCore(connected.generation + 1L, "profile_0"),
            ),
            restarted.effects,
        )
    }

    @Test
    fun `stop during validation invalidates late success`() {
        val validating = validatingSession("profile_0")
        val stopped = reducer.reduce(validating, VpnSessionEvent.StopRequested)
        val staleSuccess = reducer.reduce(
            stopped.snapshot,
            VpnSessionEvent.ValidationSucceeded(validating.generation, "profile_0"),
        )

        assertEquals(VpnState.Stopping, stopped.snapshot.state)
        assertFalse(stopped.snapshot.shouldRun)
        assertTrue(stopped.effects.contains(VpnSessionEffect.CancelOperations(1L)))
        assertTrue(
            stopped.effects.contains(
                VpnSessionEffect.StopCore(
                    coreGeneration = 1L,
                    completionGeneration = 2L,
                ),
            ),
        )
        assertEquals(stopped.snapshot, staleSuccess.snapshot)
        assertTrue(staleSuccess.effects.isEmpty())
    }

    @Test
    fun `latest switch wins while previous probe is running`() {
        val connected = connectedSession("profile_0")
        val firstSwitch = reducer.reduce(
            connected,
            VpnSessionEvent.SwitchRequested("profile_1"),
        )
        val secondSwitch = reducer.reduce(
            firstSwitch.snapshot,
            VpnSessionEvent.SwitchRequested("profile_2"),
        )
        val staleSuccess = reducer.reduce(
            secondSwitch.snapshot,
            VpnSessionEvent.SwitchSucceeded(firstSwitch.snapshot.generation, "profile_1"),
        )
        val latestSuccess = reducer.reduce(
            staleSuccess.snapshot,
            VpnSessionEvent.SwitchSucceeded(secondSwitch.snapshot.generation, "profile_2"),
        )

        assertEquals(3L, secondSwitch.snapshot.generation)
        assertTrue(secondSwitch.effects.contains(VpnSessionEffect.CancelOperations(2L)))
        assertIs<VpnSessionEffect.SelectOutbound>(secondSwitch.effects.last())
        assertEquals(secondSwitch.snapshot, staleSuccess.snapshot)
        assertEquals(VpnState.Connected, latestSuccess.snapshot.state)
        assertEquals("profile_2", latestSuccess.snapshot.runtimeProfileTag)
    }

    @Test
    fun `verified switch rollback restores previous profile without another effect`() {
        val connected = connectedSession("profile_0")
        val switching = reducer.reduce(
            connected,
            VpnSessionEvent.SwitchRequested("profile_1"),
        )
        val rolledBack = reducer.reduce(
            switching.snapshot,
            VpnSessionEvent.SwitchFailed(
                switching.snapshot.generation,
                "target validation failed",
            ),
        )

        assertEquals(VpnState.Connected, rolledBack.snapshot.state)
        assertEquals("profile_0", rolledBack.snapshot.preferredProfileTag)
        assertEquals("profile_0", rolledBack.snapshot.runtimeProfileTag)
        assertTrue(rolledBack.effects.isEmpty())
    }

    @Test
    fun `choosing the active fallback makes it preferred without a network switch`() {
        val fallbackConnected = connectedSession("profile_0").copy(
            preferredProfileTag = "profile_1",
            runtimeProfileTag = "profile_0",
        )
        val selected = reducer.reduce(
            fallbackConnected,
            VpnSessionEvent.SwitchRequested("profile_0"),
        )

        assertEquals("profile_0", selected.snapshot.preferredProfileTag)
        assertEquals("profile_0", selected.snapshot.runtimeProfileTag)
        assertEquals(fallbackConnected.generation + 1L, selected.snapshot.generation)
        assertEquals(
            listOf(VpnSessionEffect.CancelOperations(fallbackConnected.generation)),
            selected.effects,
        )
    }

    @Test
    fun `stale core callback from previous start is ignored`() {
        val first = reducer.reduce(
            VpnSessionSnapshot(),
            VpnSessionEvent.StartRequested("profile_0"),
        )
        val second = reducer.reduce(
            first.snapshot,
            VpnSessionEvent.StartRequested("profile_1"),
        )
        val stale = reducer.reduce(
            second.snapshot,
            VpnSessionEvent.CoreStarted(first.snapshot.generation),
        )

        assertEquals(second.snapshot, stale.snapshot)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun `out of order callback from current generation is ignored`() {
        val connected = connectedSession("profile_0")
        val duplicateCoreStarted = reducer.reduce(
            connected,
            VpnSessionEvent.CoreStarted(connected.generation),
        )

        assertEquals(connected, duplicateCoreStarted.snapshot)
        assertTrue(duplicateCoreStarted.effects.isEmpty())
    }

    @Test
    fun `unlock probes only after a meaningful sleep`() {
        val connected = connectedSession("profile_0")
        clock.now = 10_000L
        val locked = reducer.reduce(connected, VpnSessionEvent.ScreenLocked)
        clock.now = 39_999L
        val shortWake = reducer.reduce(locked.snapshot, VpnSessionEvent.ScreenUnlocked)

        assertTrue(shortWake.effects.isEmpty())

        clock.now = 50_000L
        val lockedAgain = reducer.reduce(shortWake.snapshot, VpnSessionEvent.ScreenLocked)
        clock.now = 80_000L
        val longWake = reducer.reduce(lockedAgain.snapshot, VpnSessionEvent.ScreenUnlocked)

        assertEquals(
            VpnSessionEffect.ValidateTunnel(connected.generation, ValidationReason.Wake),
            longWake.effects.single(),
        )
    }

    @Test
    fun `process restore starts a fresh session and resets uptime`() {
        val restored = reducer.reduce(
            VpnSessionSnapshot(connectedAtElapsedMillis = 42_000L),
            VpnSessionEvent.ProcessRestored(shouldRun = true, profileTag = "profile_3"),
        )

        assertEquals(VpnState.Recovering, restored.snapshot.state)
        assertEquals(0L, restored.snapshot.connectedAtElapsedMillis)
        assertEquals(VpnSessionEffect.StartCore(1L, "profile_3"), restored.effects.single())
    }

    @Test
    fun `boot respects autostart and requires a profile`() {
        val disabled = reducer.reduce(
            VpnSessionSnapshot(),
            VpnSessionEvent.BootCompleted(autoStart = false, profileTag = "profile_0"),
        )
        val missingProfile = reducer.reduce(
            VpnSessionSnapshot(),
            VpnSessionEvent.BootCompleted(autoStart = true, profileTag = null),
        )
        val enabled = reducer.reduce(
            VpnSessionSnapshot(),
            VpnSessionEvent.BootCompleted(autoStart = true, profileTag = "profile_0"),
        )

        assertEquals(VpnState.Stopped, disabled.snapshot.state)
        assertTrue(disabled.effects.isEmpty())
        assertTrue(missingProfile.effects.isEmpty())
        assertEquals(VpnSessionEffect.StartCore(1L, "profile_0"), enabled.effects.single())
    }

    @Test
    fun `core death recovers with a new generation and honest uptime`() {
        val connected = connectedSession("profile_0")
        val recovery = reducer.reduce(
            connected,
            VpnSessionEvent.CoreDied(connected.generation, "core stopped"),
        )

        assertEquals(VpnState.Recovering, recovery.snapshot.state)
        assertEquals(connected.generation + 1L, recovery.snapshot.generation)
        assertEquals(0L, recovery.snapshot.connectedAtElapsedMillis)
        assertTrue(recovery.effects.contains(VpnSessionEffect.StopCore(connected.generation)))
        assertTrue(
            recovery.effects.contains(
                VpnSessionEffect.StartCore(connected.generation + 1L, "profile_0"),
            ),
        )
    }

    @Test
    fun `permission revoke stops and rejects late connected state`() {
        val validating = validatingSession("profile_0")
        val revoked = reducer.reduce(validating, VpnSessionEvent.PermissionRevoked)
        val stale = reducer.reduce(
            revoked.snapshot,
            VpnSessionEvent.ValidationSucceeded(validating.generation, "profile_0"),
        )

        assertEquals(VpnState.Stopping, revoked.snapshot.state)
        assertFalse(revoked.snapshot.shouldRun)
        assertEquals("VPN permission revoked", revoked.snapshot.lastError)
        assertEquals(revoked.snapshot, stale.snapshot)
    }

    @Test
    fun `completed explicit stop reaches stopped on the stop generation`() {
        val connected = connectedSession("profile_0")
        val stopping = reducer.reduce(connected, VpnSessionEvent.StopRequested)
        val stopped = reducer.reduce(
            stopping.snapshot,
            VpnSessionEvent.StopCompleted(stopping.snapshot.generation),
        )

        assertEquals(VpnState.Stopped, stopped.snapshot.state)
        assertFalse(stopped.snapshot.shouldRun)
        assertEquals(0L, stopped.snapshot.connectedAtElapsedMillis)
        assertEquals(null, stopped.snapshot.runtimeProfileTag)
    }

    @Test
    fun `cleanup during recovery cannot complete the replacement session as stopped`() {
        val connected = connectedSession("profile_0")
        val recovery = reducer.reduce(
            connected,
            VpnSessionEvent.CoreDied(connected.generation, "core stopped"),
        )
        val cleanup = assertIs<VpnSessionEffect.StopCore>(
            recovery.effects.first { it is VpnSessionEffect.StopCore },
        )
        val staleCompletion = reducer.reduce(
            recovery.snapshot,
            VpnSessionEvent.StopCompleted(cleanup.coreGeneration),
        )

        assertEquals(null, cleanup.completionGeneration)
        assertEquals(recovery.snapshot, staleCompletion.snapshot)
    }

    @Test
    fun `failed profile never reaches connected`() {
        val validating = validatingSession("profile_0")
        val failure = reducer.reduce(
            validating,
            VpnSessionEvent.ValidationFailed(
                generation = validating.generation,
                message = "authentication failed",
                recoverable = false,
            ),
        )

        assertEquals(VpnState.Error, failure.snapshot.state)
        assertFalse(failure.snapshot.shouldRun)
        assertEquals(0L, failure.snapshot.connectedAtElapsedMillis)
        assertEquals(VpnSessionEffect.StopCore(validating.generation), failure.effects.single())
    }

    @Test
    fun `network loss recovers without inventing a new connected session`() {
        val connected = connectedSession("profile_0")
        val lost = reducer.reduce(
            connected,
            VpnSessionEvent.UpstreamChanged(available = false),
        )
        val available = reducer.reduce(
            lost.snapshot,
            VpnSessionEvent.UpstreamChanged(available = true),
        )

        assertEquals(VpnState.Recovering, lost.snapshot.state)
        assertEquals(connected.connectedAtElapsedMillis, lost.snapshot.connectedAtElapsedMillis)
        assertEquals(connected.generation + 1L, lost.snapshot.generation)
        assertEquals(
            VpnSessionEffect.CancelOperations(connected.generation),
            lost.effects.single(),
        )
        assertEquals(VpnState.Validating, available.snapshot.state)
        assertEquals(
            listOf(
                VpnSessionEffect.CancelOperations(lost.snapshot.generation),
                VpnSessionEffect.ValidateTunnel(
                    available.snapshot.generation,
                    ValidationReason.Recovery,
                ),
            ),
            available.effects,
        )
    }

    @Test
    fun `recoverable wake validation starts actor owned recovery`() {
        val connected = connectedSession("profile_0")
        val failed = reducer.reduce(
            connected.copy(state = VpnState.Validating),
            VpnSessionEvent.ValidationFailed(
                generation = connected.generation,
                message = "wake probe failed",
                recoverable = true,
                reason = ValidationReason.Wake,
            ),
        )

        assertEquals(VpnState.Recovering, failed.snapshot.state)
        assertEquals(
            VpnSessionEffect.RecoverConnection(
                generation = connected.generation,
                request = RecoveryRequest(
                    reason = "wake",
                    probeBeforeRefresh = false,
                    resetConnectionsOnSuccess = true,
                ),
            ),
            failed.effects.single(),
        )
    }

    @Test
    fun `temporary fallback schedules preferred profile retry`() {
        val validating = validatingSession("profile_0")
        val fallback = reducer.reduce(
            validating,
            VpnSessionEvent.ValidationSucceeded(
                generation = validating.generation,
                runtimeProfileTag = "profile_1",
            ),
        )

        assertEquals(VpnState.Connected, fallback.snapshot.state)
        assertEquals("profile_0", fallback.snapshot.preferredProfileTag)
        assertEquals("profile_1", fallback.snapshot.runtimeProfileTag)
        assertEquals(
            VpnSessionEffect.SchedulePreferredRetry(validating.generation),
            fallback.effects.single(),
        )
    }

    @Test
    fun `preferred retry due targets preferred while fallback remains connected`() {
        val fallback = connectedSession("profile_0").copy(runtimeProfileTag = "profile_1")
        val due = reducer.reduce(
            fallback,
            VpnSessionEvent.PreferredRetryDue(fallback.generation),
        )

        assertEquals(VpnState.Connected, due.snapshot.state)
        assertEquals(
            VpnSessionEffect.RetryPreferredOutbound(
                generation = fallback.generation,
                preferredProfileTag = "profile_0",
                runtimeProfileTag = "profile_1",
            ),
            due.effects.single(),
        )
    }

    @Test
    fun `preferred retry rollback keeps fallback and reschedules`() {
        val fallback = connectedSession("profile_0").copy(runtimeProfileTag = "profile_1")
        val rolledBack = reducer.reduce(
            fallback,
            VpnSessionEvent.PreferredRetryRolledBack(fallback.generation),
        )

        assertEquals("profile_0", rolledBack.snapshot.preferredProfileTag)
        assertEquals("profile_1", rolledBack.snapshot.runtimeProfileTag)
        assertEquals(VpnState.Connected, rolledBack.snapshot.state)
        assertEquals(
            VpnSessionEffect.SchedulePreferredRetry(fallback.generation),
            rolledBack.effects.single(),
        )
    }

    private fun validatingSession(profileTag: String): VpnSessionSnapshot {
        val started = reducer.reduce(
            VpnSessionSnapshot(),
            VpnSessionEvent.StartRequested(profileTag),
        )
        val coreStarted = reducer.reduce(
            started.snapshot,
            VpnSessionEvent.CoreStarted(started.snapshot.generation),
        )
        return reducer.reduce(
            coreStarted.snapshot,
            VpnSessionEvent.TunnelEstablished(coreStarted.snapshot.generation),
        ).snapshot
    }

    private fun connectedSession(profileTag: String): VpnSessionSnapshot {
        val validating = validatingSession(profileTag)
        clock.now = 5_000L
        return reducer.reduce(
            validating,
            VpnSessionEvent.ValidationSucceeded(validating.generation, profileTag),
        ).snapshot
    }

    private class FakeElapsedClock(var now: Long = 0L) : ElapsedClock {
        override fun nowMillis(): Long = now
    }
}
