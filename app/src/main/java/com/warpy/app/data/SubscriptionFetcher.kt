package com.warpy.app.data

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

object SubscriptionFetcher {
    private const val MAX_REDIRECTS = 5
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun fetch(value: String): Result<String> = runCatching {
        var url = requireHttpsUrl(value)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Clash/Warpy")
                .header("Accept", "text/plain, application/json, application/yaml, text/yaml")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    check(redirectCount < MAX_REDIRECTS) { "Слишком много перенаправлений подписки" }
                    url = redirectUrl(response, url)
                    return@repeat
                }
                check(response.isSuccessful) { "Сервер подписки вернул HTTP ${response.code}" }
                val body = response.body ?: error("Сервер подписки вернул пустой ответ")
                val declaredLength = body.contentLength()
                check(declaredLength <= MAX_RESPONSE_BYTES || declaredLength < 0) {
                    "Ответ подписки слишком большой"
                }
                return@runCatching body.byteStream().use(::readUtf8WithLimit)
            }
        }
        error("Слишком много перенаправлений подписки")
    }

    internal fun requireHttpsUrl(value: String): HttpUrl {
        val url = value.trim().toHttpUrlOrNull() ?: error("Некорректная ссылка подписки")
        require(url.isHttps) { "Подписка должна использовать HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Ссылка подписки не должна содержать логин или пароль в адресе"
        }
        return url
    }

    internal fun readUtf8WithLimit(
        input: InputStream,
        maxBytes: Int = MAX_RESPONSE_BYTES,
    ): String {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "Ответ подписки слишком большой" }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun redirectUrl(response: Response, current: HttpUrl): HttpUrl {
        val location = response.header("Location") ?: error("Перенаправление без адреса")
        val next = current.resolve(location) ?: error("Некорректное перенаправление подписки")
        require(next.isHttps) { "Подписка перенаправляет на небезопасный адрес" }
        require(next.username.isEmpty() && next.password.isEmpty()) {
            "Перенаправление содержит логин или пароль в адресе"
        }
        return next
    }
}
