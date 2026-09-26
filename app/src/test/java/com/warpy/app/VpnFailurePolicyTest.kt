package com.warpy.app

import com.warpy.app.vpn.classifyInitialValidationFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VpnFailurePolicyTest {
    @Test
    fun `missing validated network remains recoverable`() {
        val failure = classifyInitialValidationFailure(
            hasValidatedNetwork = false,
            probeFailure = "authentication failed",
        )

        assertTrue(failure.recoverable)
        assertEquals("Ожидание сети", failure.message)
    }

    @Test
    fun `generic SOCKS failure remains recoverable instead of disabling VPN`() {
        val failure = classifyInitialValidationFailure(
            hasValidatedNetwork = true,
            probeFailure = "SOCKS server general failure",
        )

        assertTrue(failure.recoverable)
        assertEquals("Соединение восстанавливается", failure.message)
    }

    @Test
    fun `authentication and tls failures are terminal`() {
        val authentication = classifyInitialValidationFailure(
            hasValidatedNetwork = true,
            probeFailure = "proxy authentication failed",
        )
        val tls = classifyInitialValidationFailure(
            hasValidatedNetwork = true,
            probeFailure = "x509: certificate is valid for another host",
        )

        assertFalse(authentication.recoverable)
        assertEquals(
            "Профиль не подключился: сервер отклонил данные авторизации",
            authentication.message,
        )
        assertFalse(tls.recoverable)
        assertEquals(
            "Профиль не подключился: TLS-сертификат не совпадает с SNI",
            tls.message,
        )
    }

    @Test
    fun `transport failures with a working physical network remain recoverable`() {
        val timeout = classifyInitialValidationFailure(
            hasValidatedNetwork = true,
            probeFailure = "connect timed out",
        )
        val refused = classifyInitialValidationFailure(
            hasValidatedNetwork = true,
            probeFailure = "connection refused",
        )
        val unknown = classifyInitialValidationFailure(
            hasValidatedNetwork = true,
            probeFailure = "unexpected EOF",
        )

        assertTrue(timeout.recoverable)
        assertEquals("Сервер временно не отвечает", timeout.message)
        assertTrue(refused.recoverable)
        assertEquals("Сервер временно отклонил соединение", refused.message)
        assertTrue(unknown.recoverable)
        assertEquals("Соединение восстанавливается", unknown.message)
    }
}
