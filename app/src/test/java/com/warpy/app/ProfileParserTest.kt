package com.warpy.app

import com.warpy.app.data.ProfileParser
import com.warpy.app.model.Protocol
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.util.Base64

class ProfileParserTest {

    @Test
    fun testAutoDetectsCommonProfileProtocols() {
        val vmessJson = """{"v":"2","ps":"VMess","add":"vmess.example.com","port":"443","id":"00000000-0000-4000-8000-000000000123","aid":"0","scy":"auto","net":"ws","host":"cdn.example.com","path":"/ws","tls":"tls","sni":"cdn.example.com"}"""
        val vmess = "vmess://${Base64.getEncoder().encodeToString(vmessJson.toByteArray())}"
        val fixtures = listOf(
            vmess to Protocol.Vmess,
            "ss://YWVzLTI1Ni1nY206c2VjcmV0@ss.example.com:8388#SS" to Protocol.Shadowsocks,
            "socks5://user:pass@socks.example.com:1080#SOCKS" to Protocol.Socks,
            "wg://wg.example.com:51820?pk=private&peer_pk=public&local_address=10.0.0.2%2F32#WG" to Protocol.WireGuard,
            "tuic://00000000-0000-4000-8000-000000000123:secret@tuic.example.com:443?sni=tuic.example.com#TUIC" to Protocol.Tuic,
            "hysteria://hy.example.com:443?auth=secret&peer=hy.example.com&upmbps=50&downmbps=100#Hysteria" to Protocol.Hysteria,
        )

        fixtures.forEach { (link, protocol) ->
            assertEquals(protocol, ProfileParser.parse(link).getOrThrow().protocol)
        }
    }

    @Test
    fun testParseVlessReality() {
        val link = "vless://96b0101c-0eb9-4089-9a00-111122223333@1.2.3.4:443" +
                "?security=reality&sni=yahoo.com&flow=xtls-rprx-vision" +
                "&pbk=pubkey123&sid=shortid123&fp=chrome" +
                "&alpn=h2%2Chttp%2F1.1&packetEncoding=packetaddr#My%20Reality%20Profile"

        val result = ProfileParser.parse(link)
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()

        assertEquals("My Reality Profile", profile.name)
        assertEquals(Protocol.Vless, profile.protocol)
        assertEquals("1.2.3.4", profile.server)
        assertEquals(443, profile.port)
        assertEquals("96b0101c-0eb9-4089-9a00-111122223333", profile.uuid)
        assertEquals("reality", profile.security)
        assertEquals("yahoo.com", profile.sni)
        assertEquals("xtls-rprx-vision", profile.flow)
        assertEquals("pubkey123", profile.publicKey)
        assertEquals("shortid123", profile.shortId)
        assertEquals("chrome", profile.fingerprint)
        assertEquals(listOf("h2", "http/1.1"), profile.alpn)
        assertEquals("packetaddr", profile.packetEncoding)
    }

    @Test
    fun testParseHysteria2WithObfs() {
        val link = "hysteria2://myPassword123@5.6.7.8:8443" +
                "?insecure=1&sni=google.com&obfs=salamander&obfs-password=obfsPass" +
                "&server_ports=20000-30000&hop_interval=5s&hop_interval_max=10s" +
                "&up_mbps=150&down_mbps=200&alpn=h3%2Chy2#Hysteria2%20Profile"

        val result = ProfileParser.parse(link)
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()

        assertEquals("Hysteria2 Profile", profile.name)
        assertEquals(Protocol.Hysteria2, profile.protocol)
        assertEquals("5.6.7.8", profile.server)
        assertEquals(8443, profile.port)
        assertEquals("myPassword123", profile.password)
        assertEquals("google.com", profile.sni)
        assertTrue(profile.allowInsecure)
        assertEquals("salamander", profile.hysteria2ObfsType)
        assertEquals("obfsPass", profile.hysteria2ObfsPassword)
        assertEquals("20000-30000", profile.hysteria2ServerPorts)
        assertEquals("5s", profile.hysteria2HopInterval)
        assertEquals("10s", profile.hysteria2HopIntervalMax)
        assertEquals(150, profile.hysteria2UpMbps)
        assertEquals(200, profile.hysteria2DownMbps)
        assertEquals(listOf("h3", "hy2"), profile.alpn)
    }

    @Test
    fun testParseHysteria2AcceptsBooleanInsecureParameter() {
        val result = ProfileParser.parse(
            "hysteria2://password@5.6.7.8:443?insecure=true#Profile",
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().allowInsecure)
    }

    @Test
    fun testParseHysteria2ShareLinkWithRawPlusAndAllowInsecureAlias() {
        val result = ProfileParser.parse(
            "hysteria2://AbCd123+XyZ@203.0.113.10:2443" +
                "?obfs-password=AbCd123%2BXyZ&security=tls" +
                "&sni=www.cloudflare.com&allowInsecure=true#Fallback",
        )

        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals("AbCd123+XyZ", profile.password)
        assertEquals("AbCd123+XyZ", profile.hysteria2ObfsPassword)
        assertEquals("salamander", profile.hysteria2ObfsType)
        assertTrue(profile.allowInsecure)
    }

    @Test
    fun testParseTrojan() {
        val link = "trojan://trojanPassword@9.10.11.12:443" +
                "?sni=peer-host.com&insecure=1#Trojan%20Profile"

        val result = ProfileParser.parse(link)
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()

        assertEquals("Trojan Profile", profile.name)
        assertEquals(Protocol.Trojan, profile.protocol)
        assertEquals("9.10.11.12", profile.server)
        assertEquals(443, profile.port)
        assertEquals("trojanPassword", profile.password)
        assertEquals("peer-host.com", profile.sni)
        assertTrue(profile.allowInsecure)
    }

    @Test
    fun testParseTrojanReality() {
        val result = ProfileParser.parse(
            "trojan://00000000-0000-4000-8000-000000000001@203.0.113.10:8444" +
                "?security=reality&sni=example.com" +
                "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "&sid=0123abcd&type=tcp&flow=#Trojan-Fixture",
        )

        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals(Protocol.Trojan, profile.protocol)
        assertEquals("reality", profile.security)
        assertEquals("example.com", profile.sni)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", profile.publicKey)
        assertEquals("0123abcd", profile.shortId)
        assertEquals("tcp", profile.transport)
        assertEquals("chrome", profile.fingerprint)
    }

    @Test
    fun testParseVlessWebSocket() {
        val link = "vless://96b0101c-0eb9-4089-9a00-111122223333@1.2.3.4:443" +
                "?type=ws&host=wshost.com&path=/wspath#WS%20Profile"
        val result = ProfileParser.parse(link)
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals("ws", profile.transport)
        assertEquals("wshost.com", profile.host)
        assertEquals("/wspath", profile.path)
        assertFalse(profile.multiplex)
    }

    @Test
    fun testParseVlessGrpcAndMultiplex() {
        val link = "vless://96b0101c-0eb9-4089-9a00-111122223333@1.2.3.4:443" +
                "?type=grpc&serviceName=myService&mux=1#Grpc%20Profile"
        val result = ProfileParser.parse(link)
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals("grpc", profile.transport)
        assertEquals("myService", profile.serviceName)
        assertTrue(profile.multiplex)
    }

    @Test
    fun testParseVlessTlsAndGrpcAliases() {
        val profile = ProfileParser.parse(
            "vless://96b0101c-0eb9-4089-9a00-111122223333@1.2.3.4:443" +
                "?security=tls&allow_insecure=true&type=grpc&service_name=warpy#Aliases",
        ).getOrThrow()

        assertTrue(profile.allowInsecure)
        assertEquals("warpy", profile.serviceName)
    }

    @Test
    fun testParseTrojanGrpcAliases() {
        val profile = ProfileParser.parse(
            "trojan://secret@1.2.3.4:443" +
                "?security=tls&allow_insecure=1&type=grpc&service_name=warpy#Aliases",
        ).getOrThrow()

        assertTrue(profile.allowInsecure)
        assertEquals("warpy", profile.serviceName)
    }

    @Test
    fun testParseVlessXhttpStreamUp() {
        val link = "vless://96b0101c-0eb9-4089-9a00-111122223333@1.2.3.4:443" +
            "?type=xhttp&mode=stream-up&path=%2Fwarpy&host=cdn.example.com#XHTTP%20Profile"
        val profile = ProfileParser.parse(link).getOrThrow()

        assertEquals("xhttp", profile.transport)
        assertEquals("stream-up", profile.xhttpMode)
        assertEquals("/warpy", profile.path)
        assertEquals("cdn.example.com", profile.host)
    }

    @Test
    fun testParseVlessXhttpDefaultsToStreamOne() {
        val link = "vless://96b0101c-0eb9-4089-9a00-111122223333@1.2.3.4:443?type=XHTTP"
        val profile = ProfileParser.parse(link).getOrThrow()

        assertEquals("xhttp", profile.transport)
        assertEquals("stream-one", profile.xhttpMode)
    }

    @Test
    fun testParseVlessHttpUpgradeTransport() {
        val profile = ProfileParser.parse(
            "vless://96b0101c-0eb9-4089-9a00-111122223333@1.2.3.4:443" +
                "?type=httpupgrade&path=%2Ftunnel&host=cdn.example.com#HTTPUpgrade",
        ).getOrThrow()

        assertEquals("httpupgrade", profile.transport)
        assertEquals("/tunnel", profile.path)
        assertEquals("cdn.example.com", profile.host)
    }

    @Test
    fun testNormalizesCommonTransportAliases() {
        val fixtures = mapOf(
            "h2" to "http",
            "http-upgrade" to "httpupgrade",
            "splithttp" to "xhttp",
        )

        fixtures.forEach { (source, expected) ->
            val profile = ProfileParser.parse(
                "vless://00000000-0000-4000-8000-000000000001@example.com:443?type=$source#Alias",
            ).getOrThrow()
            assertEquals(expected, profile.transport)
        }
    }

    @Test
    fun testParseShadowsocksPlugin() {
        val profile = ProfileParser.parse(
            "ss://YWVzLTI1Ni1nY206c2VjcmV0@ss.example.com:8388" +
                "?plugin=v2ray-plugin%3Btls%3Bhost%3Dcdn.example.com%3Bpath%3D%2Fws#SS",
        ).getOrThrow()

        assertEquals("v2ray-plugin", profile.plugin)
        assertEquals("tls;host=cdn.example.com;path=/ws", profile.pluginOptions)
    }

    @Test
    fun testRejectUnsupportedShadowsocksPlugin() {
        val result = ProfileParser.parse(
            "ss://YWVzLTI1Ni1nY206c2VjcmV0@ss.example.com:8388?plugin=unknown-plugin#SS",
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun testParseTrojanXhttpTransport() {
        val profile = ProfileParser.parse(
            "trojan://password@1.2.3.4:443" +
                "?type=xhttp&mode=packet-up&path=%2Ftunnel&host=cdn.example.com#Trojan-XHTTP",
        ).getOrThrow()

        assertEquals("xhttp", profile.transport)
        assertEquals("packet-up", profile.xhttpMode)
        assertEquals("/tunnel", profile.path)
        assertEquals("cdn.example.com", profile.host)
    }

    @Test
    fun testParseUnsupportedTransport() {
        val link = "vless://96b0101c-0eb9-4089-9a00-111122223333@1.2.3.4:443?type=quic"
        val result = ProfileParser.parse(link)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message?.contains("Профиль содержит пока неподдерживаемый транспорт") == true)
    }

    @Test
    fun testParseInvalidLinks() {
        val emptyLink = ""
        val invalidScheme = "ssh://method:pass@host:port"
        val missingServer = "vless://uuid@:443"
        val missingUserInfo = "vless://@host:port"

        assertTrue(ProfileParser.parse(emptyLink).isFailure)
        assertTrue(ProfileParser.parse(invalidScheme).isFailure)
        assertTrue(ProfileParser.parse(missingServer).isFailure)
        assertTrue(ProfileParser.parse(missingUserInfo).isFailure)
    }

}
