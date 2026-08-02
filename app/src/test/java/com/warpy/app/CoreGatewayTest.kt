package com.warpy.app

import com.warpy.app.vpn.shouldRetryCommandHandshake
import com.warpy.app.vpn.session.CoreCommand
import com.warpy.app.vpn.session.CoreCommandClient
import com.warpy.app.vpn.session.CoreCommandClientFactory
import com.warpy.app.vpn.session.DefaultCoreGateway
import com.warpy.app.vpn.session.ElapsedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CoreGatewayTest {
    @Test
    fun `stale command channel is closed and retried until handshake succeeds`() = runBlocking {
        val factory = FakeClientFactory(failuresBeforeSuccess = 2)
        val clock = FakeElapsedClock()
        val gateway = gateway(factory, clock)

        val result = gateway.selectOutboundWhenReady("profile_2")

        assertTrue(result.succeeded)
        assertEquals(3, result.attempts)
        assertEquals(3, factory.createdClients)
        assertEquals(3, factory.closedClients)
        assertEquals(listOf("profile_2"), factory.selectedOutbounds)
    }

    @Test
    fun `command handshake stops after the elapsed timeout`() = runBlocking {
        val factory = FakeClientFactory(failuresBeforeSuccess = Int.MAX_VALUE)
        val clock = FakeElapsedClock()
        val gateway = gateway(
            factory = factory,
            clock = clock,
            wait = { clock.now += 3_000L },
        )

        val result = gateway.selectOutboundWhenReady("profile_0")

        assertFalse(result.succeeded)
        assertEquals(3, result.attempts)
        assertEquals(3, factory.closedClients)
        assertEquals("stale command channel", result.error?.message)
    }

    @Test
    fun `connection reset uses the connections command and closes its client`() {
        val factory = FakeClientFactory()
        val gateway = gateway(factory, FakeElapsedClock())

        val result = gateway.closeConnections()

        assertTrue(result.succeeded)
        assertEquals(listOf(CoreCommand.Connections), factory.commands)
        assertEquals(1, factory.closedConnectionsCalls)
        assertEquals(1, factory.closedClients)
    }

    @Test
    fun `client creation failure is returned without escaping the gateway`() {
        val expected = IllegalStateException("command socket unavailable")
        val gateway = DefaultCoreGateway(
            clientFactory = CoreCommandClientFactory { throw expected },
            groupTag = "proxy",
            clock = FakeElapsedClock(),
            shouldRetryHandshake = { _, _ -> false },
            handshakeRetryDelayMillis = 100L,
        )

        val result = gateway.selectOutbound("profile_0")

        assertFalse(result.succeeded)
        assertEquals(expected, result.error)
    }

    private fun gateway(
        factory: FakeClientFactory,
        clock: FakeElapsedClock,
        wait: suspend (Long) -> Unit = { clock.now += it },
    ) = DefaultCoreGateway(
        clientFactory = factory,
        groupTag = "proxy",
        clock = clock,
        shouldRetryHandshake = ::shouldRetryCommandHandshake,
        handshakeRetryDelayMillis = 100L,
        wait = wait,
    )

    private class FakeElapsedClock(var now: Long = 0L) : ElapsedClock {
        override fun nowMillis(): Long = now
    }

    private class FakeClientFactory(
        private var failuresBeforeSuccess: Int = 0,
    ) : CoreCommandClientFactory {
        var createdClients = 0
        var closedClients = 0
        var closedConnectionsCalls = 0
        val commands = mutableListOf<CoreCommand>()
        val selectedOutbounds = mutableListOf<String>()

        override fun create(command: CoreCommand): CoreCommandClient {
            createdClients++
            commands += command
            return object : CoreCommandClient {
                override fun connect() {
                    if (command == CoreCommand.OutboundGroup && failuresBeforeSuccess > 0) {
                        failuresBeforeSuccess--
                        throw IllegalStateException("stale command channel")
                    }
                }

                override fun selectOutbound(groupTag: String, outboundTag: String) {
                    selectedOutbounds += outboundTag
                }

                override fun closeConnections() {
                    closedConnectionsCalls++
                }

                override fun close() {
                    closedClients++
                }
            }
        }
    }
}
