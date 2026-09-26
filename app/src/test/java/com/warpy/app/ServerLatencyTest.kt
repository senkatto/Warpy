package com.warpy.app

import com.warpy.app.vpn.measureTcpLatency
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerLatencyTest {
    @Test
    fun `measures TCP handshakes without DNS or HTTP and keeps genuinely high latency`() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val times = listOf(0L, 1_400L, 2_000L, 3_100L, 4_000L, 5_200L).iterator()
            val latency = measureTcpLatency(
                SocketFactory.getDefault(),
                InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
            ) { times.next() * 1_000_000 }
            assertEquals(1_100L, latency)
            repeat(3) { server.accept().close() }
        }
    }

    @Test
    fun `DNS resolution cannot be included in server latency`() {
        assertFailsWith<IllegalArgumentException> {
            measureTcpLatency(SocketFactory.getDefault(), InetSocketAddress.createUnresolved("server.test", 443))
        }
    }
}
