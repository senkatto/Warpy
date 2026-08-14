package com.warpy.app.vpn.session

import com.warpy.app.vpn.LocalProxyConfig
import com.warpy.app.vpn.awaitStatusCode
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class TunnelValidationRequest(
    val proxy: LocalProxyConfig,
    val maxAttempts: Int,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val retryDelayMillis: Long = 750L,
    val url: String = DEFAULT_TUNNEL_VALIDATION_URL,
    val fallbackUrls: List<String> = listOf(DEFAULT_TUNNEL_VALIDATION_FALLBACK_URL),
)

internal data class TunnelValidationAttempt(
    val statusCode: Int? = null,
    val failure: String? = null,
)

internal data class TunnelValidationResult(
    val isValid: Boolean,
    val attempts: List<TunnelValidationAttempt>,
) {
    val lastFailure: String?
        get() = attempts.lastOrNull { it.failure != null }?.failure
}

internal fun interface TunnelValidator {
    suspend fun validate(request: TunnelValidationRequest): TunnelValidationResult
}

internal class HttpTunnelValidator : TunnelValidator {
    override suspend fun validate(request: TunnelValidationRequest): TunnelValidationResult {
        require(request.maxAttempts > 0) { "maxAttempts must be positive" }
        require(request.connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(request.readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        val validationUrls = (listOf(request.url) + request.fallbackUrls).distinct()
        require(validationUrls.isNotEmpty()) { "at least one validation URL is required" }

        val authorization = request.proxy.basicAuthorization()
        val proxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress("127.0.0.1", request.proxy.port),
        )
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .proxyAuthenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", authorization)
                    .build()
            }
            .connectTimeout(request.connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(request.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(
                (request.connectTimeoutMillis + request.readTimeoutMillis).toLong(),
                TimeUnit.MILLISECONDS,
            )
            .followRedirects(false)
            .build()
        val attempts = mutableListOf<TunnelValidationAttempt>()

        repeat(request.maxAttempts) { attemptIndex ->
            currentCoroutineContext().ensureActive()
            try {
                val validationUrl = validationUrls[attemptIndex % validationUrls.size]
                val code = client.newCall(
                    Request.Builder().url(validationUrl).get().build(),
                ).awaitStatusCode()
                attempts += TunnelValidationAttempt(statusCode = code)
                if (code in 200..399) {
                    return TunnelValidationResult(isValid = true, attempts = attempts)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                attempts += TunnelValidationAttempt(
                    failure = error.message.orEmpty().ifBlank { error.toString() }.take(180),
                )
            }
            if (request.retryDelayMillis > 0L) {
                delay(request.retryDelayMillis)
            }
        }

        if (
            attempts.any { it.failure != null } &&
            probeHttpTunnel(
                proxy = request.proxy,
                authorization = authorization,
                connectTimeoutMillis = request.connectTimeoutMillis,
                readTimeoutMillis = request.readTimeoutMillis,
            )
        ) {
            attempts += TunnelValidationAttempt(statusCode = 200)
            return TunnelValidationResult(isValid = true, attempts = attempts)
        }

        return TunnelValidationResult(isValid = false, attempts = attempts)
    }
}

private suspend fun probeHttpTunnel(
    proxy: LocalProxyConfig,
    authorization: String,
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress("127.0.0.1", proxy.port),
                connectTimeoutMillis,
            )
            socket.soTimeout = readTimeoutMillis
            socket.getOutputStream().bufferedWriter(Charsets.US_ASCII).use { writer ->
                writer.write("GET $HTTP_PROBE_URL HTTP/1.1\r\n")
                writer.write("Host: $HTTP_PROBE_HOST\r\n")
                writer.write("Proxy-Authorization: $authorization\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.flush()

                val statusLine = socket.getInputStream()
                    .bufferedReader(Charsets.US_ASCII)
                    .readLine()
                    .orEmpty()
                statusLine.startsWith("HTTP/1.1 204 ") ||
                    statusLine.startsWith("HTTP/1.0 204 ")
            }
        }
    }.getOrDefault(false)
}

private fun LocalProxyConfig.basicAuthorization(): String {
    val credentials = "$username:$password".toByteArray(Charsets.UTF_8)
    return "Basic ${Base64.getEncoder().encodeToString(credentials)}"
}

private const val DEFAULT_TUNNEL_VALIDATION_URL = "https://www.gstatic.com/generate_204"
private const val DEFAULT_TUNNEL_VALIDATION_FALLBACK_URL = "https://cp.cloudflare.com/generate_204"
private const val HTTP_PROBE_URL = "http://cp.cloudflare.com/generate_204"
private const val HTTP_PROBE_HOST = "cp.cloudflare.com"
