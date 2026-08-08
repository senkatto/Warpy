package com.warpy.app

import com.warpy.app.data.SubscriptionFetcher
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubscriptionReadLimitTest {
    @Test
    fun readsResponseWithinLimit() {
        val input = ByteArrayInputStream("vless://profile".toByteArray())

        assertEquals("vless://profile", SubscriptionFetcher.readUtf8WithLimit(input, 64))
    }

    @Test
    fun rejectsResponseAboveLimit() {
        val input = ByteArrayInputStream(ByteArray(65) { 'a'.code.toByte() })

        assertFailsWith<IllegalArgumentException> {
            SubscriptionFetcher.readUtf8WithLimit(input, 64)
        }
    }

    @Test
    fun preservesUtf8Text() {
        val source = "Профиль подключения"
        val input = ByteArrayInputStream(source.toByteArray(Charsets.UTF_8))

        assertEquals(source, SubscriptionFetcher.readUtf8WithLimit(input, 128))
    }
}
