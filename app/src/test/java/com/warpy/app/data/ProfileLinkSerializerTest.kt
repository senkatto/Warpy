package com.warpy.app.data

import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileLinkSerializerTest {
    @Test
    fun everySupportedProtocolCanBeSharedAndImportedAgain() {
        val profiles = listOf(
            VpnProfile(
                name = "VLESS XHTTP",
                protocol = Protocol.Vless,
                server = "vless.example.com",
                port = 443,
                uuid = "00000000-0000-4000-8000-000000000001",
                security = "reality",
                sni = "www.example.com",
                publicKey = "public-key",
                shortId = "abcd",
                transport = "xhttp",
                path = "/xhttp",
                xhttpMode = "stream-up",
                packetEncoding = "packetaddr",
            ),
            VpnProfile(
                name = "Hysteria2",
                protocol = Protocol.Hysteria2,
                server = "hy2.example.com",
                port = 8443,
                password = "secret+value",
                sni = "hy2.example.com",
                alpn = listOf("h3"),
                hysteria2ObfsType = "salamander",
                hysteria2ObfsPassword = "obfs-secret",
                hysteria2UpMbps = 50,
                hysteria2DownMbps = 100,
            ),
            VpnProfile(
                name = "Trojan WS",
                protocol = Protocol.Trojan,
                server = "trojan.example.com",
                port = 443,
                password = "trojan-secret",
                security = "tls",
                sni = "cdn.example.com",
                transport = "ws",
                host = "cdn.example.com",
                path = "/ws",
            ),
            VpnProfile(
                name = "VMess gRPC",
                protocol = Protocol.Vmess,
                server = "vmess.example.com",
                port = 443,
                uuid = "00000000-0000-4000-8000-000000000002",
                security = "tls",
                sni = "vmess.example.com",
                transport = "grpc",
                serviceName = "warpy",
                encryption = "auto",
            ),
            VpnProfile(
                name = "Shadowsocks",
                protocol = Protocol.Shadowsocks,
                server = "ss.example.com",
                port = 8388,
                password = "ss-secret",
                encryption = "2022-blake3-aes-128-gcm",
            ),
            VpnProfile(
                name = "SOCKS",
                protocol = Protocol.Socks,
                server = "socks.example.com",
                port = 1080,
                username = "user",
                password = "pass",
            ),
            VpnProfile(
                name = "WireGuard",
                protocol = Protocol.WireGuard,
                server = "wg.example.com",
                port = 51820,
                privateKey = "private-key",
                peerPublicKey = "public-key",
                preSharedKey = "pre-shared-key",
                localAddress = "10.0.0.2/32",
                mtu = 1380,
            ),
            VpnProfile(
                name = "TUIC",
                protocol = Protocol.Tuic,
                server = "tuic.example.com",
                port = 443,
                uuid = "00000000-0000-4000-8000-000000000003",
                password = "tuic-secret",
                sni = "tuic.example.com",
                alpn = listOf("h3"),
                congestionControl = "bbr",
                udpRelayMode = "native",
            ),
            VpnProfile(
                name = "Hysteria",
                protocol = Protocol.Hysteria,
                server = "hysteria.example.com",
                port = 443,
                password = "hysteria-secret",
                sni = "hysteria.example.com",
                hysteria2ObfsPassword = "obfs-secret",
                hysteria2UpMbps = 25,
                hysteria2DownMbps = 75,
            ),
            VpnProfile(
                name = "Naive QUIC",
                protocol = Protocol.Naive,
                server = "naive.example.com",
                port = 443,
                username = "alice",
                password = "s@cret",
                sni = "front.example.com",
                alpn = listOf("h2", "http/1.1"),
                naiveQuic = true,
            ),
        )

        profiles.forEach { original ->
            val link = ProfileLinkSerializer.serialize(original).getOrThrow()
            assertTrue(link.isNotBlank(), original.protocol.name)
            val parsed = ProfileParser.parse(link).getOrThrow()
            assertEquals(original.protocol, parsed.protocol)
            assertEquals(original.server, parsed.server)
            assertEquals(original.port, parsed.port)
            assertEquals(original.name, parsed.name)
            assertEquals(original.transport, parsed.transport)
            assertEquals(original.uuid, parsed.uuid)
            assertEquals(original.password, parsed.password)
        }
    }

    @Test
    fun importedRawLinkIsSharedWithoutRewriting() {
        val raw = "vless://id@example.com:443?security=tls#Original"
        val profile = VpnProfile(
            name = "Original",
            protocol = Protocol.Vless,
            server = "example.com",
            port = 443,
            uuid = "id",
            raw = raw,
        )

        assertEquals(raw, ProfileLinkSerializer.serialize(profile).getOrThrow())
    }
}
