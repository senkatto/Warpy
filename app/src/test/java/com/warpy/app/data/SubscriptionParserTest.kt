package com.warpy.app.data

import com.warpy.app.model.Protocol
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubscriptionParserTest {
    @Test
    fun parsesDirectAndUrlSafeBase64LinkLists() {
        val links = listOf(
            "vless://00000000-0000-4000-8000-000000000001@vless.example.com:443?security=tls#VLESS",
            "socks5://user:pass@socks.example.com:1080#SOCKS",
        ).joinToString("\n")
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(links.toByteArray(Charsets.UTF_8))

        val parsed = SubscriptionParser.parse(encoded).getOrThrow()

        assertEquals(listOf(Protocol.Vless, Protocol.Socks), parsed.profiles.map { it.protocol })
    }

    @Test
    fun parsesSingBoxJsonWithoutTreatingUtilityOutboundsAsErrors() {
        val payload = """
            {
              "outbounds": [
                {"type":"direct","tag":"direct"},
                {
                  "type":"vless",
                  "tag":"Reality",
                  "server":"vless.example.com",
                  "server_port":443,
                  "uuid":"00000000-0000-4000-8000-000000000001",
                  "flow":"xtls-rprx-vision",
                  "packet_encoding":"packetaddr",
                  "tls":{"enabled":true,"server_name":"www.example.com","reality":{"enabled":true,"public_key":"key","short_id":"abcd"}},
                  "transport":{"type":"xhttp","path":"/api","mode":"stream-up"}
                }
              ]
            }
        """.trimIndent()

        val parsed = SubscriptionParser.parse(payload).getOrThrow()
        val profile = parsed.profiles.single()

        assertEquals(1, parsed.skipped)
        assertEquals(Protocol.Vless, profile.protocol)
        assertEquals("reality", profile.security)
        assertEquals("xhttp", profile.transport)
        assertEquals("stream-up", profile.xhttpMode)
        assertEquals("packetaddr", profile.packetEncoding)
    }

    @Test
    fun parsesEndpointOnlySingBoxJsonAndSkipsIncompleteProfiles() {
        val payload = """
            {
              "outbounds": [
                {"type":"vless","tag":"Incomplete","server":"broken.example.com","server_port":443}
              ],
              "endpoints": [
                {
                  "type":"wireguard",
                  "tag":"WireGuard endpoint",
                  "server":"wg.example.com",
                  "server_port":51820,
                  "private_key":"private-key",
                  "peer_public_key":"public-key",
                  "local_address":["10.0.0.2/32"]
                }
              ]
            }
        """.trimIndent()

        val parsed = SubscriptionParser.parse(payload).getOrThrow()

        assertEquals(1, parsed.skipped)
        assertEquals(Protocol.WireGuard, parsed.profiles.single().protocol)
    }

    @Test
    fun parsesWhitespaceSeparatedLinks() {
        val payload = """
            vless://00000000-0000-4000-8000-000000000001@one.example.com:443
            socks5://user:pass@two.example.com:1080
        """.trimIndent().replace('\n', ' ')

        assertEquals(2, SubscriptionParser.parse(payload).getOrThrow().profiles.size)
    }

    @Test
    fun skipsMalformedLinksWithoutDiscardingValidProfiles() {
        val payload = """
            vless://missing-host
            socks5://user:pass@socks.example.com:1080#SOCKS
        """.trimIndent()

        val parsed = SubscriptionParser.parse(payload).getOrThrow()

        assertEquals(1, parsed.skipped)
        assertEquals(Protocol.Socks, parsed.profiles.single().protocol)
    }

    @Test
    fun skipsMalformedStructuredProfilesWithoutDiscardingValidProfiles() {
        val payload = """
            {
              "outbounds": [
                {
                  "type":"vless",
                  "tag":"Broken XHTTP",
                  "server":"broken.example.com",
                  "server_port":443,
                  "uuid":"00000000-0000-4000-8000-000000000001",
                  "transport":{"type":"xhttp","mode":"unsupported"}
                },
                {
                  "type":"vless",
                  "tag":"Valid WS",
                  "server":"valid.example.com",
                  "server_port":443,
                  "uuid":"00000000-0000-4000-8000-000000000002",
                  "transport":{"type":"ws","path":"/vpn"}
                }
              ]
            }
        """.trimIndent()

        val parsed = SubscriptionParser.parse(payload).getOrThrow()

        assertEquals(1, parsed.skipped)
        assertEquals("Valid WS", parsed.profiles.single().name)
    }

    @Test
    fun keepsProfilesWithSharedCredentialsAndDifferentTransports() {
        val payload = """
            vless://00000000-0000-4000-8000-000000000001@vpn.example.com:443?type=ws&path=%2Fws#WS
            vless://00000000-0000-4000-8000-000000000001@vpn.example.com:443?type=grpc&service_name=vpn#GRPC
        """.trimIndent()

        val profiles = SubscriptionParser.parse(payload).getOrThrow().profiles

        assertEquals(2, profiles.size)
        assertEquals(setOf("ws", "grpc"), profiles.map { it.transport }.toSet())
    }

    @Test
    fun parsesClashYamlAndNormalizesTransportAliases() {
        val payload = """
            proxies:
              - name: Clash H2
                type: vless
                server: h2.example.com
                port: 443
                uuid: 00000000-0000-4000-8000-000000000001
                tls: true
                servername: h2.example.com
                network: h2
                h2-opts:
                  path: /h2
                  host:
                    - cdn.example.com
        """.trimIndent()

        val profile = SubscriptionParser.parse(payload).getOrThrow().profiles.single()

        assertEquals(Protocol.Vless, profile.protocol)
        assertEquals("http", profile.transport)
        assertEquals("/h2", profile.path)
        assertEquals("cdn.example.com", profile.host)
    }

    @Test
    fun preservesNumericClashPasswordsAsText() {
        val payload = """
            proxies:
              - name: Numeric password
                type: trojan
                server: trojan.example.com
                port: 443
                password: 123456
        """.trimIndent()

        val profile = SubscriptionParser.parse(payload).getOrThrow().profiles.single()

        assertEquals(Protocol.Trojan, profile.protocol)
        assertEquals("123456", profile.password)
    }

    @Test
    fun rejectsHtmlAndOversizedPayloads() {
        assertTrue(SubscriptionParser.parse("<html><body>login</body></html>").isFailure)
        val oversized = "x".repeat(SubscriptionFetcher.MAX_RESPONSE_BYTES + 1)
        assertTrue(SubscriptionParser.parse(oversized).isFailure)
    }

    @Test
    fun rejectsUnsafeSubscriptionUrls() {
        assertFailsWith<IllegalArgumentException> {
            SubscriptionFetcher.requireHttpsUrl("http://example.com/sub")
        }
        assertFailsWith<IllegalArgumentException> {
            SubscriptionFetcher.requireHttpsUrl("https://user:pass@example.com/sub")
        }
        assertEquals("example.com", SubscriptionFetcher.requireHttpsUrl("https://example.com/sub").host)
    }
}
