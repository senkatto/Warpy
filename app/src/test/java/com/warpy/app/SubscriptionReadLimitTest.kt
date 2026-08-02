package com.warpy.app

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubscriptionReadLimitTest {
    @Test
    fun readsResponseWithinLimit() {
        val input = ByteArrayInputStream("vless://profile".toByteArray())

        assertEquals("vless://profile", input.readUtf8WithLimit(64))
    }

    @Test
    fun rejectsResponseAboveLimit() {
        val input = ByteArrayInputStream(ByteArray(65) { 'a'.code.toByte() })

        assertFailsWith<IllegalArgumentException> {
            input.readUtf8WithLimit(64)
        }
    }

    @Test
    fun preservesUtf8Text() {
        val source = "Профиль подключения"
        val input = ByteArrayInputStream(source.toByteArray(Charsets.UTF_8))

        assertEquals(source, input.readUtf8WithLimit(128))
    }
}
