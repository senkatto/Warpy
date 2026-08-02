package com.warpy.app

import com.warpy.app.vpn.LocalProxyConfig
import com.warpy.app.vpn.session.CancellableJobOwner
import com.warpy.app.vpn.session.HttpTunnelValidator
import com.warpy.app.vpn.session.TunnelValidationRequest
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class CancellableJobOwnerTest {
    @Test
    fun `a newer operation cancels the previous owner job`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val owner = CancellableJobOwner(scope)
            var firstCancelled = false
            val firstStarted = CompletableDeferred<Unit>()
            val first = owner.launch {
                try {
                    firstStarted.complete(Unit)
                    delay(Long.MAX_VALUE)
                } finally {
                    firstCancelled = true
                }
            }
            firstStarted.await()

            owner.launch { Unit }.join()
            first.join()

            assertTrue(first.isCancelled)
            assertTrue(firstCancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stop releases lifecycle lock within a second while validation is stalled`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            proxy.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val lifecycleMutex = Mutex()
            val owner = CancellableJobOwner(scope)
            try {
                owner.launch {
                    lifecycleMutex.withLock {
                        HttpTunnelValidator().validate(
                            TunnelValidationRequest(
                                proxy = LocalProxyConfig(proxy.port, "user", "password"),
                                maxAttempts = 1,
                                connectTimeoutMillis = 30_000,
                                readTimeoutMillis = 30_000,
                                url = "http://probe.warpy.test/generate_204",
                            ),
                        )
                    }
                }
                assertNotNull(proxy.takeRequest(2, TimeUnit.SECONDS))

                val elapsedMillis = measureTimeMillis {
                    owner.cancel()
                    withTimeout(1_000L) {
                        lifecycleMutex.withLock { Unit }
                    }
                }

                assertTrue(elapsedMillis < 1_000L, "Stop took ${elapsedMillis}ms")
            } finally {
                scope.cancel()
            }
        }
    }
}
