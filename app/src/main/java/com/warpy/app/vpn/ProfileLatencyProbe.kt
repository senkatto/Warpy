package com.warpy.app.vpn

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object ProfileLatencyProbe {
    // Two controller calls, each with a 2s connection and 6s read timeout.
    const val MAX_DURATION_MILLIS = 16_000L

    suspend fun measure(port: Int, secret: String, index: Int): Int {
        currentCoroutineContext().ensureActive()
        return try {
            probe(port, secret, index, "https%3A%2F%2Fwww.gstatic.com%2Fgenerate_204")
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            probe(port, secret, index, "https%3A%2F%2Fcp.cloudflare.com%2Fgenerate_204")
        }
    }

    private fun probe(port: Int, secret: String, index: Int, url: String): Int {
        val path = "/proxies/profile_$index/delay?url=$url&timeout=4000"
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 6_000
            val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
            writer.write("GET $path HTTP/1.1\r\n")
            writer.write("Host: 127.0.0.1:$port\r\n")
            writer.write("Authorization: Bearer $secret\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
            val response = socket.getInputStream().bufferedReader().readText()
            if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) {
                throw IOException(response.lineSequence().firstOrNull().orEmpty())
            }
            return Regex("\"delay\"\\s*:\\s*(\\d+)")
                .find(response)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: throw IOException("empty delay")
        }
    }
}
