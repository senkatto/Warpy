package com.warpy.app

import com.warpy.app.vpn.session.DnsEndpoint
import com.warpy.app.vpn.session.UdpDnsExchanger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UdpDnsExchangerTest {
    @Test
    fun `exchanges a DNS packet with a local UDP server`() {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { server ->
            val expectedResponse = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val worker = thread(name = "local-dns-test", isDaemon = true) {
                val request = DatagramPacket(ByteArray(512), 512)
                server.receive(request)
                server.send(
                    DatagramPacket(
                        expectedResponse,
                        expectedResponse.size,
                        request.address,
                        request.port,
                    ),
                )
            }
            val protectedSockets = AtomicInteger()
            val exchanger = UdpDnsExchanger(
                protectSocket = { protectedSockets.incrementAndGet() },
                timeoutMillis = 500,
            )

            val response = exchanger.exchange(
                message = byteArrayOf(0x10, 0x20),
                endpoints = listOf(DnsEndpoint(loopback, server.localPort)),
            )

            worker.join(1_000)
            assertContentEquals(expectedResponse, response)
            assertEquals(1, protectedSockets.get())
        }
    }

    @Test
    fun `falls back to the next DNS endpoint after a timeout`() {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { server ->
            DatagramSocket(0, loopback).use { blackhole ->
                val worker = thread(name = "fallback-dns-test", isDaemon = true) {
                    val request = DatagramPacket(ByteArray(512), 512)
                    server.receive(request)
                    val response = byteArrayOf(0x55)
                    server.send(DatagramPacket(response, response.size, request.address, request.port))
                }
                val protectedSockets = AtomicInteger()
                val exchanger = UdpDnsExchanger(
                    protectSocket = { protectedSockets.incrementAndGet() },
                    timeoutMillis = 50,
                )

                val response = exchanger.exchange(
                    message = byteArrayOf(0x33),
                    endpoints = listOf(
                        DnsEndpoint(loopback, blackhole.localPort),
                        DnsEndpoint(loopback, server.localPort),
                    ),
                )

                worker.join(1_000)
                assertContentEquals(byteArrayOf(0x55), response)
                assertEquals(2, protectedSockets.get())
            }
        }
    }

    @Test
    fun `surfaces the final DNS timeout when every endpoint fails`() {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { blackhole ->
            val exchanger = UdpDnsExchanger(
                protectSocket = {},
                timeoutMillis = 20,
            )

            assertFailsWith<SocketTimeoutException> {
                exchanger.exchange(
                    message = byteArrayOf(0x01),
                    endpoints = listOf(DnsEndpoint(loopback, blackhole.localPort)),
                )
            }
        }
    }
}
