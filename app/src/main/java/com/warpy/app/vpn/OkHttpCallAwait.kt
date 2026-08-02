package com.warpy.app.vpn

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal suspend fun Call.awaitStatusCode(): Int = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            continuation.resumeWith(Result.failure(error))
        }

        override fun onResponse(call: Call, response: Response) {
            val statusCode = response.use { it.code }
            continuation.resumeWith(Result.success(statusCode))
        }
    })
}
