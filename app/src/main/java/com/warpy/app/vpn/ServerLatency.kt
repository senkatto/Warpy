package com.warpy.app.vpn

import java.net.InetSocketAddress
import javax.net.SocketFactory

internal fun measureTcpLatency(
    socketFactory: SocketFactory,
    address: InetSocketAddress,
    nowNanos: () -> Long = System::nanoTime,
): Long {
    require(!address.isUnresolved) { "Resolve the server on the physical network before measuring" }
    var best = Long.MAX_VALUE
    repeat(3) {
        socketFactory.createSocket().use { socket ->
            val start = nowNanos()
            socket.connect(address, 2_500)
            best = minOf(best, nowNanos() - start)
        }
    }
    return ((best + 999_999) / 1_000_000).coerceAtLeast(1)
}
