package com.warpy.app

import com.warpy.app.vpn.LocalProxyConfig
import com.warpy.app.vpn.session.HttpTunnelValidator
import com.warpy.app.vpn.session.TunnelValidationRequest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class TunnelValidatorTest {
    @Test
    fun `validates through the authenticated local proxy`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(
                MockResponse()
                    .setResponseCode(407)
                    .addHeader("Proxy-Authenticate", "Basic realm=\"Warpy\""),
            )
            proxy.enqueue(MockResponse().setResponseCode(204))
            proxy.start()

            val result = HttpTunnelValidator().validate(request(proxy.port))

            assertTrue(result.isValid)
            assertEquals(204, result.attempts.single().statusCode)
            assertEquals(null, proxy.takeRequest().getHeader("Proxy-Authorization"))
            assertEquals(expectedAuthorization(), proxy.takeRequest().getHeader("Proxy-Authorization"))
        }
    }

    @Test
    fun `retries a rejected response and reports only final success`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(503))
            proxy.enqueue(MockResponse().setResponseCode(204))
            proxy.start()

            val result = HttpTunnelValidator().validate(
                request(proxy.port).copy(maxAttempts = 2, retryDelayMillis = 1L),
            )

            assertTrue(result.isValid)
            assertEquals(listOf(503, 204), result.attempts.map { it.statusCode })
            assertEquals(2, proxy.requestCount)
        }
    }

    @Test
    fun `uses a fallback URL after the primary probe fails`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(503))
            proxy.enqueue(MockResponse().setResponseCode(204))
            proxy.start()

            val result = HttpTunnelValidator().validate(
                request(proxy.port).copy(
                    maxAttempts = 2,
                    retryDelayMillis = 1L,
                    url = "https://primary.warpy.test/generate_204",
                    fallbackUrls = listOf("http://fallback.warpy.test/generate_204"),
                ),
            )

            assertTrue(result.isValid)
            assertTrue(proxy.takeRequest().requestLine.contains("primary.warpy.test"))
            assertTrue(proxy.takeRequest().requestLine.contains("fallback.warpy.test"))
        }
    }

    @Test
    fun `returns a typed failure after the bounded attempts`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(503))
            proxy.enqueue(MockResponse().setResponseCode(502))
            proxy.start()

            val result = HttpTunnelValidator().validate(
                request(proxy.port).copy(maxAttempts = 2, retryDelayMillis = 1L),
            )

            assertFalse(result.isValid)
            assertEquals(listOf(503, 502), result.attempts.map { it.statusCode })
        }
    }

    @Test
    fun `cancelling validation closes the stalled proxy call promptly`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            proxy.start()
            val validation = async(Dispatchers.IO) {
                HttpTunnelValidator().validate(
                    request(proxy.port).copy(
                        connectTimeoutMillis = 30_000,
                        readTimeoutMillis = 30_000,
                    ),
                )
            }
            proxy.takeRequest(2, TimeUnit.SECONDS)

            val elapsedMillis = measureTimeMillis { validation.cancelAndJoin() }

            assertTrue(elapsedMillis < 1_000L, "Cancellation took ${elapsedMillis}ms")
        }
    }

    private fun request(port: Int): TunnelValidationRequest = TunnelValidationRequest(
        proxy = LocalProxyConfig(port, USERNAME, PASSWORD),
        maxAttempts = 1,
        connectTimeoutMillis = 2_000,
        readTimeoutMillis = 2_000,
        url = "http://probe.warpy.test/generate_204",
    )

    private fun expectedAuthorization(): String {
        val credentials = "$USERNAME:$PASSWORD".toByteArray(Charsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(credentials)}"
    }

    private companion object {
        const val USERNAME = "test-user"
        const val PASSWORD = "test-password"
    }
}
