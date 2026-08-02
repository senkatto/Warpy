package com.warpy.app

import com.warpy.app.vpn.LocalProxyBindPolicy
import com.warpy.app.vpn.LocalProxyConfig
import com.warpy.app.vpn.LocalProxyStartupRetrier
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalProxyBindPolicyTest {
    @Test
    fun `retries a nested bind conflict`() {
        val error = IllegalStateException(
            "core start failed",
            IllegalArgumentException("listen tcp 127.0.0.1:45678: bind: address already in use"),
        )

        assertTrue(LocalProxyBindPolicy.isAddressInUse(error))
    }

    @Test
    fun `does not retry unrelated startup failures`() {
        assertFalse(LocalProxyBindPolicy.isAddressInUse(IllegalStateException("invalid configuration")))
    }

    @Test
    fun `occupied startup port is replaced before the next attempt`() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket().use { occupied ->
            occupied.reuseAddress = false
            occupied.bind(InetSocketAddress(loopback, 0))
            val replacementPort = ServerSocket().use { candidate ->
                candidate.bind(InetSocketAddress(loopback, 0))
                candidate.localPort
            }
            var allocations = 0
            var conflicts = 0

            val started = LocalProxyStartupRetrier.start(
                maxAttempts = 3,
                allocateProxy = {
                    val port = if (allocations++ == 0) occupied.localPort else replacementPort
                    LocalProxyConfig(port, "user", "password")
                },
                onBindConflict = { conflicts++ },
            ) { proxy ->
                ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress(loopback, proxy.port))
                }
            }

            started.use {
                assertEquals(replacementPort, it.localPort)
                assertEquals(2, allocations)
                assertEquals(1, conflicts)
                assertTrue(occupied.isBound)
            }
        }
    }
}
