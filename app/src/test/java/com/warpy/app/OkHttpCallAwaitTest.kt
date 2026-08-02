package com.warpy.app

import com.warpy.app.vpn.awaitStatusCode
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpCallAwaitTest {
    @Test
    fun cancellationClosesHungCallPromptly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val call = OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
                .newCall(Request.Builder().url(server.url("/probe")).build())

            val probe = async(Dispatchers.IO) { call.awaitStatusCode() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            val elapsedMillis = measureTimeMillis { probe.cancelAndJoin() }

            assertTrue("The underlying OkHttp call was not cancelled", call.isCanceled())
            assertTrue("Cancellation took ${elapsedMillis}ms", elapsedMillis < 1_000)
        }
    }
}
