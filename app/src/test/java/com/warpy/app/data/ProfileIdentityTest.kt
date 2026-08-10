package com.warpy.app.data

import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileIdentityTest {
    @Test
    fun exactConnectionDuplicatesReuseTheExistingProfile() {
        val existing = VpnProfile(
            name = "My name",
            protocol = Protocol.Vless,
            server = "vpn.example.com",
            port = 443,
            uuid = "00000000-0000-4000-8000-000000000001",
            transport = "ws",
            path = "/vpn",
            raw = "original-link",
        )
        val imported = existing.copy(name = "Subscription name", group = "Provider", raw = "new-link")

        val result = mergeImportedProfiles(listOf(existing), listOf(imported))!!

        assertEquals(listOf(existing.copy(group = "Provider")), result.profiles)
        assertEquals(0, result.importedIndex)
    }

    @Test
    fun sharedCredentialsDoNotCollapseDifferentConnections() {
        val websocket = VpnProfile(
            name = "WS",
            protocol = Protocol.Vless,
            server = "vpn.example.com",
            port = 443,
            uuid = "00000000-0000-4000-8000-000000000001",
            sni = "one.example.com",
            transport = "ws",
            path = "/ws",
        )
        val grpc = websocket.copy(
            name = "gRPC",
            sni = "two.example.com",
            transport = "grpc",
            path = "",
            serviceName = "vpn",
        )

        val result = mergeImportedProfiles(listOf(websocket), listOf(grpc))!!

        assertEquals(listOf(websocket, grpc), result.profiles)
        assertEquals(1, result.importedIndex)
    }

    @Test
    fun subscriptionNameUsesFragmentThenProviderHost() {
        assertEquals(
            "BlancVPN",
            subscriptionDisplayName("https://0123456789abcdef.withprovider.example/sub#BlancVPN"),
        )
        assertEquals(
            "PROVIDER",
            subscriptionDisplayName("https://0123456789abcdef.withprovider.example/sub"),
        )
    }
}
