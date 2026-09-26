package com.warpy.app.vpn.session

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class DnsEndpoint(
    val address: InetAddress,
    val port: Int = DNS_PORT,
)

internal class UdpDnsExchanger(
    private val protectSocket: (DatagramSocket) -> Unit,
    private val timeoutMillis: Int,
    private val packetSize: Int = DNS_PACKET_MAX_SIZE,
    private val registerCancellation: ((() -> Unit) -> Unit) = {},
) {
    fun exchange(message: ByteArray, endpoints: List<DnsEndpoint>): ByteArray {
        require(message.isNotEmpty()) { "DNS message must not be empty" }
        require(endpoints.isNotEmpty()) { "DNS endpoints must not be empty" }

        val cancelled = AtomicBoolean()
        val activeSocket = AtomicReference<DatagramSocket?>()
        registerCancellation {
            cancelled.set(true)
            activeSocket.get()?.close()
        }

        var lastError: Exception? = null
        for (endpoint in endpoints) {
            if (cancelled.get()) throw InterruptedIOException("DNS exchange cancelled")
            try {
                DatagramSocket().use { socket ->
                    activeSocket.set(socket)
                    if (cancelled.get()) throw InterruptedIOException("DNS exchange cancelled")
                    protectSocket(socket)
                    socket.connect(endpoint.address, endpoint.port)
                    socket.soTimeout = timeoutMillis
                    socket.send(
                        DatagramPacket(
                            message,
                            message.size,
                            endpoint.address,
                            endpoint.port,
                        ),
                    )
                    val buffer = ByteArray(packetSize)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    return response.data.copyOf(response.length)
                }
            } catch (error: Exception) {
                lastError = error
            } finally {
                activeSocket.set(null)
            }
        }
        throw lastError ?: IllegalStateException("DNS exchange failed")
    }
}

private const val DNS_PORT = 53
private const val DNS_PACKET_MAX_SIZE = 65_535
