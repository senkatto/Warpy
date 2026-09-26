package com.warpy.app

import com.warpy.app.vpn.session.DnsEndpoint
import com.warpy.app.vpn.session.UdpDnsExchanger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UdpDnsExchangerTest {
    @Test
    fun `cancellation closes a blocked DNS socket without trying more endpoints`() {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { blackhole ->
            val worker = Executors.newSingleThreadExecutor()
            val started = CountDownLatch(1)
            val protectedSockets = AtomicInteger()
            var cancel: () -> Unit = {}
            val exchanger = UdpDnsExchanger(
                protectSocket = { protectedSockets.incrementAndGet(); started.countDown() },
                timeoutMillis = 30_000,
                registerCancellation = { cancel = it },
            )
            try {
                val result = worker.submit<ByteArray> {
                    exchanger.exchange(
                        byteArrayOf(1),
                        List(3) { DnsEndpoint(loopback, blackhole.localPort) },
                    )
                }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                cancel()
                assertFailsWith<ExecutionException> { result.get(1, TimeUnit.SECONDS) }
                assertEquals(1, protectedSockets.get())
            } finally {
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun `preserves an EDNS response larger than 512 bytes`() {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { server ->
            val expected = ByteArray(4096) { (it % 251).toByte() }
            val worker = thread(isDaemon = true) {
                val request = DatagramPacket(ByteArray(512), 512)
                server.receive(request)
                server.send(DatagramPacket(expected, expected.size, request.address, request.port))
            }
            val response = UdpDnsExchanger({}, 500).exchange(
                byteArrayOf(0x10, 0x20),
                listOf(DnsEndpoint(loopback, server.localPort)),
            )
            worker.join(1_000)
            assertContentEquals(expected, response)
        }
    }

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
