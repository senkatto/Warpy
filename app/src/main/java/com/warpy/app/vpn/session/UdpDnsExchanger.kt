package com.warpy.app.vpn.session

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

internal data class DnsEndpoint(
    val address: InetAddress,
    val port: Int = DNS_PORT,
)

internal class UdpDnsExchanger(
    private val protectSocket: (DatagramSocket) -> Unit,
    private val timeoutMillis: Int,
    private val packetSize: Int = DNS_PACKET_MAX_SIZE,
) {
    fun exchange(message: ByteArray, endpoints: List<DnsEndpoint>): ByteArray {
        require(message.isNotEmpty()) { "DNS message must not be empty" }
        require(endpoints.isNotEmpty()) { "DNS endpoints must not be empty" }

        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                DatagramSocket().use { socket ->
                    protectSocket(socket)
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
            }
        }
        throw lastError ?: IllegalStateException("DNS exchange failed")
    }
}

private const val DNS_PORT = 53
private const val DNS_PACKET_MAX_SIZE = 512
