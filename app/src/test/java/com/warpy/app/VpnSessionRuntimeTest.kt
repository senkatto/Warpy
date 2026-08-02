package com.warpy.app

import com.warpy.app.model.VpnState
import com.warpy.app.vpn.session.ConnectionRecoveryResult
import com.warpy.app.vpn.session.ElapsedClock
import com.warpy.app.vpn.session.OutboundSwitchResult
import com.warpy.app.vpn.session.PreferredRetryResult
import com.warpy.app.vpn.session.RecoveryRequest
import com.warpy.app.vpn.session.SessionValidationResult
import com.warpy.app.vpn.session.ValidationReason
import com.warpy.app.vpn.session.VpnSessionEvent
import com.warpy.app.vpn.session.VpnSessionOperations
import com.warpy.app.vpn.session.VpnSessionReducer
import com.warpy.app.vpn.session.VpnSessionRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class VpnSessionRuntimeTest {
    @Test
    fun `successful start publishes connected only after validation`() = runBlocking {
        val operations = FakeOperations()
        val runtime = newRuntime(this, operations)

        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)

        assertEquals(listOf("start:1:profile_0", "validate:1:Initial"), operations.calls)
        assertEquals("profile_0", runtime.snapshot().runtimeProfileTag)
        runtime.close()
    }

    @Test
    fun `stop cancels a stalled validation and closes resources exactly once`() = runBlocking {
        val validationStarted = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            validation = {
                validationStarted.complete(Unit)
                CompletableDeferred<SessionValidationResult>().await()
            },
        )
        val runtime = newRuntime(this, operations)

        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        validationStarted.await()
        runtime.dispatch(VpnSessionEvent.StopRequested)
        awaitState(runtime, VpnState.Stopped)

        assertEquals(1, operations.calls.count { it.startsWith("stop:") })
        runtime.close()
    }

    @Test
    fun `replacement session stops the old core before starting the new one`() = runBlocking {
        val operations = FakeOperations()
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)
        operations.calls.clear()

        runtime.dispatch(VpnSessionEvent.StartRequested("profile_1"))
        awaitState(runtime, VpnState.Connected)

        assertEquals(
            listOf("cancel:1", "stop:1", "start:2:profile_1", "validate:2:Initial"),
            operations.calls,
        )
        runtime.close()
    }

    @Test
    fun `forced restart rebuilds the active profile and validates the new core`() = runBlocking {
        val operations = FakeOperations()
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)
        operations.calls.clear()

        runtime.dispatch(VpnSessionEvent.RestartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)

        assertEquals(
            listOf("cancel:1", "stop:1", "start:2:profile_0", "validate:2:Initial"),
            operations.calls,
        )
        runtime.close()
    }

    @Test
    fun `startup failure cleans resources once and remains error`() = runBlocking {
        val operations = FakeOperations(startFailure = IllegalStateException("broken config"))
        val runtime = newRuntime(this, operations)

        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Error)
        awaitCall(operations, "stop:1")

        assertEquals("broken config", runtime.snapshot().lastError)
        assertEquals(1, operations.calls.count { it == "stop:1" })
        runtime.close()
    }

    @Test
    fun `stop cancels stalled startup and closes resources exactly once`() = runBlocking {
        val startupStarted = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            start = {
                startupStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
        )
        val runtime = newRuntime(this, operations)

        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        startupStarted.await()
        runtime.dispatch(VpnSessionEvent.StopRequested)
        awaitState(runtime, VpnState.Stopped)

        assertEquals(1, operations.calls.count { it == "stop:1" })
        assertEquals(0, operations.calls.count { it.startsWith("validate:") })
        runtime.close()
    }

    @Test
    fun `terminal initial validation failure closes resources exactly once`() = runBlocking {
        val operations = FakeOperations(
            validation = {
                SessionValidationResult(
                    succeeded = false,
                    message = "invalid credentials",
                    recoverable = false,
                )
            },
        )
        val runtime = newRuntime(this, operations)

        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Error)
        awaitCall(operations, "stop:1")

        assertEquals("invalid credentials", runtime.snapshot().lastError)
        assertEquals(1, operations.calls.count { it == "stop:1" })
        runtime.close()
    }

    @Test
    fun `recoverable initial network outage preserves intent without closing core`() = runBlocking {
        val operations = FakeOperations(
            validation = {
                SessionValidationResult(
                    succeeded = false,
                    message = "Ожидание сети",
                    recoverable = true,
                )
            },
            recovery = {
                ConnectionRecoveryResult.Deferred("Ожидание сети")
            },
        )
        val runtime = newRuntime(this, operations)

        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitCall(operations, "recover:1:initial:false:false")
        awaitState(runtime, VpnState.Recovering)

        assertEquals(true, runtime.snapshot().shouldRun)
        assertEquals("Ожидание сети", runtime.snapshot().lastError)
        assertEquals(0, operations.calls.count { it.startsWith("stop:") })
        runtime.close()
    }

    @Test
    fun `exhausted initial recovery stops core and reports error`() = runBlocking {
        val operations = FakeOperations(
            validation = {
                SessionValidationResult(
                    succeeded = false,
                    message = "connection failed",
                    recoverable = true,
                )
            },
            recovery = {
                ConnectionRecoveryResult.Exhausted("Не удалось установить соединение")
            },
        )
        val runtime = newRuntime(this, operations)

        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Error)
        awaitCall(operations, "stop:1")

        assertEquals(false, runtime.snapshot().shouldRun)
        assertEquals("Не удалось установить соединение", runtime.snapshot().lastError)
        assertEquals(1, operations.calls.count { it == "stop:1" })
        runtime.close()
    }

    @Test
    fun `exhausted background recovery fully restarts tunnel resources`() = runBlocking {
        val operations = FakeOperations(
            recovery = {
                ConnectionRecoveryResult.Exhausted("Соединение прервано")
            },
        )
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)
        operations.calls.clear()

        runtime.dispatch(
            VpnSessionEvent.RecoveryRequested(
                runtime.snapshot().generation,
                RecoveryRequest("network-handoff", false, true),
            ),
        )
        awaitState(runtime, VpnState.Connected)

        assertEquals(true, runtime.snapshot().shouldRun)
        assertEquals(null, runtime.snapshot().lastError)
        assertEquals(1, operations.calls.count { it.startsWith("stop:") })
        assertEquals(1, operations.calls.count { it.startsWith("start:") })
        runtime.close()
    }

    @Test
    fun `successful switch publishes the selected runtime profile`() = runBlocking {
        val operations = FakeOperations()
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)
        operations.calls.clear()

        runtime.dispatch(VpnSessionEvent.SwitchRequested("profile_1"))
        awaitProfile(runtime, "profile_1")

        assertEquals(
            listOf("cancel:1", "switch:2:profile_0:profile_1"),
            operations.calls,
        )
        assertEquals(VpnState.Connected, runtime.snapshot().state)
        runtime.close()
    }

    @Test
    fun `verified rollback remains connected to the previous profile`() = runBlocking {
        val operations = FakeOperations(
            switch = { OutboundSwitchResult.RolledBack("target failed") },
        )
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)

        runtime.dispatch(VpnSessionEvent.SwitchRequested("profile_1"))
        awaitState(runtime, VpnState.Connected)

        assertEquals("profile_0", runtime.snapshot().preferredProfileTag)
        assertEquals("profile_0", runtime.snapshot().runtimeProfileTag)
        assertEquals("target failed", runtime.snapshot().lastError)
        runtime.close()
    }

    @Test
    fun `failed target and rollback restart the preferred profile transactionally`() = runBlocking {
        val operations = FakeOperations(
            switch = { OutboundSwitchResult.Failed("rollback failed") },
        )
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)
        operations.calls.clear()

        runtime.dispatch(VpnSessionEvent.SwitchRequested("profile_1"))
        awaitProfile(runtime, "profile_1")

        assertEquals(
            listOf(
                "cancel:1",
                "switch:2:profile_0:profile_1",
                "cancel:2",
                "stop:2",
                "start:3:profile_1",
                "validate:3:Recovery",
            ),
            operations.calls,
        )
        runtime.close()
    }

    @Test
    fun `latest switch cancels a stalled previous switch`() = runBlocking {
        val firstSwitchStarted = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            switch = { profileTag ->
                if (profileTag == "profile_1") {
                    firstSwitchStarted.complete(Unit)
                    CompletableDeferred<OutboundSwitchResult>().await()
                } else {
                    OutboundSwitchResult.Succeeded(profileTag)
                }
            },
        )
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)
        operations.calls.clear()

        runtime.dispatch(VpnSessionEvent.SwitchRequested("profile_1"))
        firstSwitchStarted.await()
        runtime.dispatch(VpnSessionEvent.SwitchRequested("profile_2"))
        awaitProfile(runtime, "profile_2")

        assertEquals(
            listOf(
                "cancel:1",
                "switch:2:profile_0:profile_1",
                "cancel:2",
                "switch:3:profile_0:profile_2",
            ),
            operations.calls,
        )
        runtime.close()
    }

    @Test
    fun `late non cancellable switch result cannot overwrite the latest profile`() = runBlocking {
        val firstSwitchStarted = CompletableDeferred<Unit>()
        val firstSwitchResult = CompletableDeferred<OutboundSwitchResult>()
        val operations = FakeOperations(
            switch = { profileTag ->
                if (profileTag == "profile_1") {
                    firstSwitchStarted.complete(Unit)
                    withContext(NonCancellable) { firstSwitchResult.await() }
                } else {
                    OutboundSwitchResult.Succeeded(profileTag)
                }
            },
        )
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)

        runtime.dispatch(VpnSessionEvent.SwitchRequested("profile_1"))
        firstSwitchStarted.await()
        runtime.dispatch(VpnSessionEvent.SwitchRequested("profile_2"))
        awaitProfile(runtime, "profile_2")
        firstSwitchResult.complete(OutboundSwitchResult.RolledBack("late rollback"))
        delay(20L)

        assertEquals(VpnState.Connected, runtime.snapshot().state)
        assertEquals("profile_2", runtime.snapshot().preferredProfileTag)
        assertEquals("profile_2", runtime.snapshot().runtimeProfileTag)
        runtime.close()
    }

    @Test
    fun `stop cancels a stalled profile switch and closes resources once`() = runBlocking {
        val switchStarted = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            switch = {
                switchStarted.complete(Unit)
                CompletableDeferred<OutboundSwitchResult>().await()
            },
        )
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)

        runtime.dispatch(VpnSessionEvent.SwitchRequested("profile_1"))
        switchStarted.await()
        runtime.dispatch(VpnSessionEvent.StopRequested)
        awaitState(runtime, VpnState.Stopped)

        assertEquals(1, operations.calls.count { it.startsWith("stop:") })
        runtime.close()
    }

    @Test
    fun `successful bounded recovery returns to connected`() = runBlocking {
        val operations = FakeOperations()
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)
        operations.calls.clear()
        val request = RecoveryRequest("network-handoff", true, true)

        runtime.dispatch(
            VpnSessionEvent.RecoveryRequested(runtime.snapshot().generation, request),
        )
        awaitState(runtime, VpnState.Connected)

        assertEquals(
            listOf("recover:1:network-handoff:true:true"),
            operations.calls,
        )
        runtime.close()
    }

    @Test
    fun `latest recovery cancels a stalled previous recovery`() = runBlocking {
        val firstRecoveryStarted = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            recovery = { request ->
                if (request.reason == "first") {
                    firstRecoveryStarted.complete(Unit)
                    CompletableDeferred<ConnectionRecoveryResult>().await()
                } else {
                    ConnectionRecoveryResult.Succeeded("profile_0")
                }
            },
        )
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)
        operations.calls.clear()
        val generation = runtime.snapshot().generation

        runtime.dispatch(
            VpnSessionEvent.RecoveryRequested(
                generation,
                RecoveryRequest("first", false, true),
            ),
        )
        firstRecoveryStarted.await()
        runtime.dispatch(
            VpnSessionEvent.RecoveryRequested(
                generation,
                RecoveryRequest("second", false, true),
            ),
        )
        awaitState(runtime, VpnState.Connected)

        assertEquals(
            listOf(
                "recover:1:first:false:true",
                "recover:1:second:false:true",
            ),
            operations.calls,
        )
        runtime.close()
    }

    @Test
    fun `stop cancels stalled recovery and closes resources once`() = runBlocking {
        val recoveryStarted = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            recovery = {
                recoveryStarted.complete(Unit)
                CompletableDeferred<ConnectionRecoveryResult>().await()
            },
        )
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)

        runtime.dispatch(
            VpnSessionEvent.RecoveryRequested(
                runtime.snapshot().generation,
                RecoveryRequest("wake", false, true),
            ),
        )
        recoveryStarted.await()
        runtime.dispatch(VpnSessionEvent.StopRequested)
        awaitState(runtime, VpnState.Stopped)

        assertEquals(1, operations.calls.count { it.startsWith("stop:") })
        runtime.close()
    }

    @Test
    fun `late recovery success after network loss is rejected`() = runBlocking {
        val recoveryStarted = CompletableDeferred<Unit>()
        val recoveryResult = CompletableDeferred<ConnectionRecoveryResult>()
        val operations = FakeOperations(
            recovery = {
                recoveryStarted.complete(Unit)
                withContext(NonCancellable) { recoveryResult.await() }
            },
        )
        val runtime = newRuntime(this, operations)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)

        runtime.dispatch(
            VpnSessionEvent.RecoveryRequested(
                runtime.snapshot().generation,
                RecoveryRequest("wake", false, true),
            ),
        )
        recoveryStarted.await()
        runtime.dispatch(VpnSessionEvent.UpstreamChanged(available = false))
        val lostGeneration = runtime.snapshot().generation
        recoveryResult.complete(ConnectionRecoveryResult.Succeeded("profile_0"))
        delay(20L)

        assertEquals(VpnState.Recovering, runtime.snapshot().state)
        assertEquals(lostGeneration, runtime.snapshot().generation)
        runtime.close()
    }

    @Test
    fun `temporary fallback retries and restores the preferred profile`() = runBlocking {
        val operations = FakeOperations(
            validation = {
                SessionValidationResult(
                    succeeded = true,
                    runtimeProfileTag = "profile_1",
                )
            },
        )
        val runtime = newRuntime(this, operations, preferredRetryDelayMillis = 5L)

        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitProfile(runtime, "profile_0")

        assertEquals(
            1,
            operations.calls.count { it == "retry:1:profile_1:profile_0" },
        )
        runtime.close()
    }

    @Test
    fun `verified preferred retry rollback schedules another retry`() = runBlocking {
        var retryCount = 0
        val operations = FakeOperations(
            validation = {
                SessionValidationResult(
                    succeeded = true,
                    runtimeProfileTag = "profile_1",
                )
            },
            preferredRetry = { preferred, _ ->
                retryCount += 1
                if (retryCount == 1) {
                    PreferredRetryResult.RolledBack
                } else {
                    PreferredRetryResult.Succeeded(preferred)
                }
            },
        )
        val runtime = newRuntime(this, operations, preferredRetryDelayMillis = 5L)

        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitProfile(runtime, "profile_0")

        assertEquals(2, retryCount)
        runtime.close()
    }

    @Test
    fun `stop cancels a delayed preferred retry`() = runBlocking {
        val operations = FakeOperations(
            validation = {
                SessionValidationResult(
                    succeeded = true,
                    runtimeProfileTag = "profile_1",
                )
            },
        )
        val runtime = newRuntime(this, operations, preferredRetryDelayMillis = 60_000L)
        runtime.dispatch(VpnSessionEvent.StartRequested("profile_0"))
        awaitState(runtime, VpnState.Connected)

        runtime.dispatch(VpnSessionEvent.StopRequested)
        awaitState(runtime, VpnState.Stopped)
        delay(20L)

        assertEquals(0, operations.calls.count { it.startsWith("retry:") })
        runtime.close()
    }

    private fun newRuntime(
        scope: CoroutineScope,
        operations: FakeOperations,
        preferredRetryDelayMillis: Long = 300_000L,
    ) = VpnSessionRuntime(
        scope = scope,
        reducer = VpnSessionReducer(ElapsedClock { 5_000L }),
        operations = operations,
        preferredRetryDelayMillis = preferredRetryDelayMillis,
    )

    private suspend fun awaitState(runtime: VpnSessionRuntime, state: VpnState) {
        withTimeout(2_000L) {
            while (runtime.snapshot().state != state) delay(5L)
        }
    }

    private suspend fun awaitCall(operations: FakeOperations, call: String) {
        withTimeout(2_000L) {
            while (call !in operations.calls) delay(5L)
        }
    }

    private suspend fun awaitProfile(runtime: VpnSessionRuntime, profileTag: String) {
        withTimeout(2_000L) {
            while (runtime.snapshot().runtimeProfileTag != profileTag ||
                runtime.snapshot().state != VpnState.Connected
            ) {
                delay(5L)
            }
        }
    }

    private class FakeOperations(
        private val startFailure: Throwable? = null,
        private val start: suspend () -> Unit = {},
        private val validation: suspend () -> SessionValidationResult = {
            SessionValidationResult(succeeded = true)
        },
        private val switch: suspend (String) -> OutboundSwitchResult = { profileTag ->
            OutboundSwitchResult.Succeeded(profileTag)
        },
        private val recovery: suspend (RecoveryRequest) -> ConnectionRecoveryResult = {
            ConnectionRecoveryResult.Succeeded("profile_0")
        },
        private val preferredRetry: suspend (String, String) -> PreferredRetryResult =
            { preferredProfileTag, _ ->
                PreferredRetryResult.Succeeded(preferredProfileTag)
            },
    ) : VpnSessionOperations {
        val calls = CopyOnWriteArrayList<String>()

        override suspend fun startCore(generation: Long, profileTag: String) {
            calls += "start:$generation:$profileTag"
            startFailure?.let { throw it }
            start()
        }

        override suspend fun stopCore(coreGeneration: Long) {
            calls += "stop:$coreGeneration"
        }

        override suspend fun validateTunnel(
            generation: Long,
            reason: ValidationReason,
        ): SessionValidationResult {
            calls += "validate:$generation:$reason"
            return validation()
        }

        override suspend fun switchOutbound(
            generation: Long,
            profileTag: String,
            previousRuntimeProfileTag: String,
        ): OutboundSwitchResult {
            calls += "switch:$generation:$previousRuntimeProfileTag:$profileTag"
            return switch(profileTag)
        }

        override suspend fun recoverConnection(
            generation: Long,
            request: RecoveryRequest,
        ): ConnectionRecoveryResult {
            calls += "recover:$generation:${request.reason}:${request.probeBeforeRefresh}:${request.resetConnectionsOnSuccess}"
            return recovery(request)
        }

        override suspend fun retryPreferredOutbound(
            generation: Long,
            preferredProfileTag: String,
            runtimeProfileTag: String,
        ): PreferredRetryResult {
            calls += "retry:$generation:$runtimeProfileTag:$preferredProfileTag"
            return preferredRetry(preferredProfileTag, runtimeProfileTag)
        }

        override fun cancelOperations(generation: Long) {
            calls += "cancel:$generation"
        }
    }
}
