package com.warpy.app

import com.warpy.app.model.VpnState
import com.warpy.app.vpn.session.PublishedVpnStatus
import com.warpy.app.vpn.session.VpnSessionPublicationCodec
import com.warpy.app.vpn.session.VpnSessionSnapshot
import com.warpy.app.vpn.session.toPublication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnSessionPublicationTest {
    @Test
    fun `connected publication is derived entirely from the session snapshot`() {
        val publication = VpnSessionSnapshot(
            generation = 7L,
            state = VpnState.Connected,
            shouldRun = true,
            preferredProfileTag = "profile_2",
            runtimeProfileTag = "profile_1",
            connectedAtElapsedMillis = 42_000L,
        ).toPublication()

        assertEquals(PublishedVpnStatus.Connected, publication.status)
        assertEquals(42_000L, publication.connectedAtElapsedMillis)
        assertEquals(1, publication.activeOutboundIndex)
        assertEquals("profile_1", publication.runtimeProfileTag)
    }

    @Test
    fun `transitional states publish connecting without inventing uptime`() {
        listOf(
            VpnState.Starting,
            VpnState.Validating,
            VpnState.Recovering,
            VpnState.Stopping,
        ).forEach { state ->
            val publication = VpnSessionSnapshot(
                state = state,
                connectedAtElapsedMillis = 0L,
            ).toPublication()

            assertEquals(PublishedVpnStatus.Connecting, publication.status)
            assertEquals(0L, publication.connectedAtElapsedMillis)
        }
    }

    @Test
    fun `publication codec round trips one complete observer snapshot`() {
        val original = VpnSessionSnapshot(
            generation = 11L,
            state = VpnState.Recovering,
            shouldRun = true,
            preferredProfileTag = "profile_4",
            runtimeProfileTag = "profile_3",
            connectedAtElapsedMillis = 91_000L,
        ).toPublication()

        assertEquals(
            original,
            VpnSessionPublicationCodec.decode(VpnSessionPublicationCodec.encode(original)),
        )
    }

    @Test
    fun `publication codec rejects partial or unknown payloads`() {
        assertNull(VpnSessionPublicationCodec.decode(null))
        assertNull(VpnSessionPublicationCodec.decode("{\"schema\":1}"))
        assertNull(VpnSessionPublicationCodec.decode("{\"schema\":99}"))
        assertNull(VpnSessionPublicationCodec.decode("not-json"))
    }

    @Test
    fun `publication codec preserves an absent runtime profile`() {
        val original = VpnSessionSnapshot(
            generation = 3L,
            state = VpnState.Starting,
            shouldRun = true,
            runtimeProfileTag = null,
        ).toPublication()

        assertEquals(
            original,
            VpnSessionPublicationCodec.decode(VpnSessionPublicationCodec.encode(original)),
        )
    }
}
