package com.warpy.app

import com.warpy.app.vpn.VpnLaunchResult
import com.warpy.app.vpn.executeVpnServiceLaunch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VpnLaunchPolicyTest {
    @Test
    fun `successful service start is reported exactly once`() {
        var calls = 0

        val result = executeVpnServiceLaunch(launch = { calls += 1 })

        assertIs<VpnLaunchResult.Started>(result)
        assertEquals(1, calls)
    }

    @Test
    fun `background foreground-service restriction becomes an actionable failure`() {
        val result = executeVpnServiceLaunch(
            launch = { throw IllegalStateException("background start restricted") },
            isBackgroundStartRestricted = { true },
        )

        val failure = assertIs<VpnLaunchResult.Failed>(result)
        assertEquals(
            "Android не разрешил запустить VPN в фоне. Откройте Warpy и повторите",
            failure.message,
        )
    }

    @Test
    fun `security exception becomes an actionable failure`() {
        val result = executeVpnServiceLaunch(
            launch = { throw SecurityException("permission denied") },
        )

        val failure = assertIs<VpnLaunchResult.Failed>(result)
        assertEquals(
            "Android запретил запуск VPN. Проверьте разрешение VPN в настройках системы",
            failure.message,
        )
    }

    @Test
    fun `unexpected runtime start failure is contained`() {
        val result = executeVpnServiceLaunch(
            launch = { throw IllegalStateException("binder failure") },
        )

        val failure = assertIs<VpnLaunchResult.Failed>(result)
        assertEquals("Не удалось запустить VPN", failure.message)
    }
}
