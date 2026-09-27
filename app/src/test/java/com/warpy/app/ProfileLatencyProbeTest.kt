package com.warpy.app

import com.warpy.app.vpn.ProfileLatencyProbe
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class ProfileLatencyProbeTest {
    @Test
    fun `successful primary check does not request fallback`() = runBlocking {
        MockWebServer().use { controller ->
            controller.enqueue(response(200, "{\"delay\":219}"))
            controller.start()

            assertEquals(219, ProfileLatencyProbe.measure(controller.port, "test-secret", 2))
            assertEquals(1, controller.requestCount)
            val request = controller.takeRequest()
            assertTrue(request.path.orEmpty().startsWith("/proxies/profile_2/delay?"))
            assertEquals("Bearer test-secret", request.getHeader("Authorization"))
            assertTrue(request.path.orEmpty().contains("www.gstatic.com"))
        }
    }

    @Test
    fun `failed Google request uses independent destination through same profile`() = runBlocking {
        MockWebServer().use { controller ->
            controller.enqueue(response(503, "{\"message\":\"timeout\"}"))
            controller.enqueue(response(200, "{\"delay\":143}"))
            controller.start()

            assertEquals(143, ProfileLatencyProbe.measure(controller.port, "test-secret", 2))
            val primary = controller.takeRequest()
            val fallback = controller.takeRequest()
            assertTrue(primary.path.orEmpty().contains("www.gstatic.com"))
            assertTrue(fallback.path.orEmpty().contains("cp.cloudflare.com"))
            assertTrue(fallback.path.orEmpty().startsWith("/proxies/profile_2/delay?"))
            assertEquals("Bearer test-secret", fallback.getHeader("Authorization"))
        }
    }

    @Test
    fun `two failed destinations still report failure`() = runBlocking {
        MockWebServer().use { controller ->
            controller.enqueue(response(503, "{}"))
            controller.enqueue(response(503, "{}"))
            controller.start()

            assertFailsWith<IOException> {
                ProfileLatencyProbe.measure(controller.port, "test-secret", 2)
            }
            assertEquals(2, controller.requestCount)
        }
    }

    @Test
    fun `zero delay is not reported as a successful measurement`() = runBlocking {
        MockWebServer().use { controller ->
            controller.enqueue(response(200, "{\"delay\":0}"))
            controller.enqueue(response(200, "{\"delay\":155}"))
            controller.start()

            assertEquals(155, ProfileLatencyProbe.measure(controller.port, "test-secret", 2))
            assertEquals(2, controller.requestCount)
        }
    }

    private fun response(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setBody(body)
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
}
