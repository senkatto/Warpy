package com.warpy.app

import com.warpy.app.vpn.COMMAND_HANDSHAKE_RETRY_DELAY_MS
import com.warpy.app.vpn.MAX_COMMAND_HANDSHAKE_ATTEMPTS
import com.warpy.app.vpn.NETWORK_CHANGE_DEBOUNCE_MS
import com.warpy.app.vpn.UpstreamIdentity
import com.warpy.app.vpn.isHandoverCandidatePhysicalNetwork
import com.warpy.app.vpn.isUsablePhysicalNetwork
import com.warpy.app.vpn.physicalNetworkPriority
import com.warpy.app.vpn.shouldRetryCommandHandshake
import com.warpy.app.vpn.shouldResetConnectionsAfterSleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionRecoveryPolicyTest {
    @Test
    fun `only a validated usable physical network is accepted`() {
        assertTrue(
            isUsablePhysicalNetwork(
                hasInternet = true,
                isValidated = true,
                isSuspended = false,
                isVpn = false,
                isBlocked = false,
            ),
        )

        assertFalse(isUsablePhysicalNetwork(true, false, false, false, false))
        assertFalse(isUsablePhysicalNetwork(true, true, true, false, false))
        assertFalse(isUsablePhysicalNetwork(true, true, false, true, false))
        assertFalse(isUsablePhysicalNetwork(true, true, false, false, true))
    }

    @Test
    fun `handover accepts an internet network before Android validation completes`() {
        assertTrue(isHandoverCandidatePhysicalNetwork(true, false, false, false))
        assertFalse(isHandoverCandidatePhysicalNetwork(false, false, false, false))
        assertFalse(isHandoverCandidatePhysicalNetwork(true, true, false, false))
        assertFalse(isHandoverCandidatePhysicalNetwork(true, false, true, false))
        assertFalse(isHandoverCandidatePhysicalNetwork(true, false, false, true))
    }

    @Test
    fun `network identity tracks handle DNS and metered state`() {
        val wifiBeforeSleep = UpstreamIdentity(
            networkHandle = 151L,
            interfaceName = "wlan0",
            dnsServers = listOf("192.168.1.1"),
            isMetered = false,
        )
        val wifiAfterSleep = UpstreamIdentity(152L, "wlan0")

        assertFalse(wifiBeforeSleep == wifiAfterSleep)
        assertFalse(wifiBeforeSleep == wifiBeforeSleep.copy(dnsServers = listOf("1.1.1.1")))
        assertFalse(wifiBeforeSleep == wifiBeforeSleep.copy(isMetered = true))
    }

    @Test
    fun `validated wifi wins over cellular and current network breaks equal ties`() {
        val cellular = physicalNetworkPriority(
            isValidated = true,
            hasEthernet = false,
            hasWifi = false,
            hasCellular = true,
            isMetered = true,
            isCurrent = true,
        )
        val wifi = physicalNetworkPriority(
            isValidated = true,
            hasEthernet = false,
            hasWifi = true,
            hasCellular = false,
            isMetered = false,
            isCurrent = false,
        )
        val currentWifi = physicalNetworkPriority(true, false, true, false, false, true)
        val otherWifi = physicalNetworkPriority(true, false, true, false, false, false)

        assertTrue(wifi > cellular)
        assertTrue(currentWifi > otherWifi)
        assertTrue(
            physicalNetworkPriority(true, false, false, true, true, false) >
                physicalNetworkPriority(false, false, true, false, false, false),
        )
    }

    @Test
    fun `validated network wins while an unvalidated handover remains usable`() {
        val validatedCellular = physicalNetworkPriority(true, false, false, true, true, false)
        val pendingWifi = physicalNetworkPriority(false, false, true, false, false, true)

        assertTrue(isHandoverCandidatePhysicalNetwork(true, false, false, false))
        assertTrue(validatedCellular > pendingWifi)
    }

    @Test
    fun `system preferred cellular beats fading validated wifi during handover`() {
        val fadingWifi = physicalNetworkPriority(
            isValidated = true,
            hasEthernet = false,
            hasWifi = true,
            hasCellular = false,
            isMetered = false,
            isCurrent = true,
        )
        val preferredCellular = physicalNetworkPriority(
            isValidated = true,
            hasEthernet = false,
            hasWifi = false,
            hasCellular = true,
            isMetered = true,
            isCurrent = false,
            isSystemPreferred = true,
        )

        assertTrue(preferredCellular > fadingWifi)
    }

    @Test
    fun `network callback bursts use a short debounce window`() {
        assertEquals(350L, NETWORK_CHANGE_DEBOUNCE_MS)
    }

    @Test
    fun `long screen off session resets stale app connections`() {
        assertFalse(shouldResetConnectionsAfterSleep(29_999L))
        assertTrue(shouldResetConnectionsAfterSleep(30_000L))
        assertTrue(shouldResetConnectionsAfterSleep(10 * 60_000L))
    }

    @Test
    fun `command handshake retries are bounded by attempts and elapsed time`() {
        assertTrue(shouldRetryCommandHandshake(failedAttempts = 1, elapsedMillis = 0L))
        assertTrue(
            shouldRetryCommandHandshake(
                failedAttempts = MAX_COMMAND_HANDSHAKE_ATTEMPTS - 1,
                elapsedMillis = 4_999L,
            ),
        )
        assertFalse(shouldRetryCommandHandshake(MAX_COMMAND_HANDSHAKE_ATTEMPTS, 0L))
        assertFalse(shouldRetryCommandHandshake(1, 5_000L))
        assertEquals(100L, COMMAND_HANDSHAKE_RETRY_DELAY_MS)
    }
}
