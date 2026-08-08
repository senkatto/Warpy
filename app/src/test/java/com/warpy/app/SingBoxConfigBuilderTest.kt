package com.warpy.app

import com.warpy.app.model.AppSettings
import com.warpy.app.model.AppTunnelMode
import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import com.warpy.app.data.ProfileParser
import com.warpy.app.vpn.LocalProxyConfig
import com.warpy.app.vpn.SingBoxConfigBuilder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

class SingBoxConfigBuilderTest {
    private val profile = VpnProfile(
        name = "test",
        protocol = Protocol.Hysteria2,
        server = "203.0.113.1",
        port = 443,
        password = "secret",
        allowInsecure = true,
    )

    @Test
    fun `auto detected protocols produce selectable sing box configs`() {
        val vmessJson = """{"v":"2","ps":"VMess","add":"vmess.example.com","port":"443","id":"00000000-0000-4000-8000-000000000123","aid":"0","scy":"auto","net":"ws","host":"cdn.example.com","path":"/ws","tls":"tls","sni":"cdn.example.com"}"""
        val links = listOf(
            "vmess://${Base64.getEncoder().encodeToString(vmessJson.toByteArray())}" to "vmess",
            "ss://YWVzLTI1Ni1nY206c2VjcmV0@ss.example.com:8388#SS" to "shadowsocks",
            "socks5://user:pass@socks.example.com:1080#SOCKS" to "socks",
            "wg://wg.example.com:51820?pk=private&peer_pk=public&local_address=10.0.0.2%2F32#WG" to "wireguard",
            "tuic://00000000-0000-4000-8000-000000000123:secret@tuic.example.com:443?sni=tuic.example.com#TUIC" to "tuic",
            "hysteria://hy.example.com:443?auth=secret&peer=hy.example.com&upmbps=50&downmbps=100#Hysteria" to "hysteria",
        )

        links.forEach { (link, expectedType) ->
            val detected = ProfileParser.parse(link).getOrThrow()
            val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(detected))))
            val selector = root.getJSONArray("outbounds")
                .let { outbounds -> (0 until outbounds.length()).map(outbounds::getJSONObject) }
                .single { it.optString("tag") == "proxy" }

            assertEquals("profile_0", selector.getJSONArray("outbounds").getString(0))
            if (expectedType == "wireguard") {
                assertEquals(expectedType, root.getJSONArray("endpoints").getJSONObject(0).getString("type"))
            } else {
                assertEquals(expectedType, root.getJSONArray("outbounds").getJSONObject(0).getString("type"))
            }
        }
    }

    @Test
    fun `regular config does not expose a local proxy`() {
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(profile))))
        val inbounds = root.getJSONArray("inbounds")
        val tags = (0 until inbounds.length()).map { inbounds.getJSONObject(it).getString("tag") }

        assertFalse("health-proxy-in" in tags)
        assertFalse("speedtest-in" in tags)
    }

    @Test
    fun `runtime local proxy requires session credentials`() {
        val root = JSONObject(
            SingBoxConfigBuilder.build(
                AppSettings(profiles = listOf(profile)),
                localProxy = LocalProxyConfig(
                    port = 45678,
                    username = "session-user",
                    password = "session-password",
                ),
            ),
        )
        val inbounds = root.getJSONArray("inbounds")
        val proxy = (0 until inbounds.length())
            .map(inbounds::getJSONObject)
            .single { it.optString("tag") == "health-proxy-in" }
        val user = proxy.getJSONArray("users").getJSONObject(0)

        assertEquals("127.0.0.1", proxy.getString("listen"))
        assertEquals(45678, proxy.getInt("listen_port"))
        assertEquals("session-user", user.getString("username"))
        assertEquals("session-password", user.getString("password"))
    }

    @Test
    fun `profile switch interrupts stale app connections`() {
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(profile))))
        val outbounds = root.getJSONArray("outbounds")
        val selector = (0 until outbounds.length())
            .map(outbounds::getJSONObject)
            .first { it.optString("tag") == "proxy" }

        assertTrue(selector.getBoolean("interrupt_exist_connections"))
    }

    @Test
    fun `quic is rejected only when explicitly enabled`() {
        val vless = profile.copy(
            protocol = Protocol.Vless,
            uuid = "00000000-0000-4000-8000-000000000001",
            password = "",
        )
        val vlessRules = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(vless))))
            .getJSONObject("route")
            .getJSONArray("rules")
        val hysteriaRules = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(profile))))
            .getJSONObject("route")
            .getJSONArray("rules")
        val manualVlessRules = JSONObject(
            SingBoxConfigBuilder.build(AppSettings(profiles = listOf(vless), blockQuic = true)),
        ).getJSONObject("route").getJSONArray("rules")
        val manualHysteriaRules = JSONObject(
            SingBoxConfigBuilder.build(AppSettings(profiles = listOf(profile), blockQuic = true)),
        ).getJSONObject("route").getJSONArray("rules")

        assertFalse(vlessRules.hasQuicBlock())
        assertFalse(hysteriaRules.hasQuicBlock())
        assertTrue(manualVlessRules.hasQuicBlock())
        assertTrue(manualHysteriaRules.hasQuicBlock())
    }

    @Test
    fun `automatic mtu uses runtime value`() {
        val root = JSONObject(
            SingBoxConfigBuilder.build(
                AppSettings(profiles = listOf(profile), mtu = 0),
                dynamicMtu = 1280,
            ),
        )

        assertEquals(1280, root.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
    }

    @Test
    fun `hysteria2 does not add port hopping without port hopping options`() {
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(profile))))
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)

        assertEquals(443, outbound.getInt("server_port"))
        assertFalse(outbound.has("hop_interval"))
        assertTrue(outbound.getJSONObject("tls").getBoolean("insecure"))
    }

    @Test
    fun `hysteria2 preserves explicit sni in generated tls`() {
        val explicitSni = profile.copy(sni = "front.example.net")
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(explicitSni))))
        val tls = root.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")

        assertEquals("front.example.net", tls.getString("server_name"))
        assertTrue(tls.getBoolean("insecure"))
    }

    @Test
    fun `vless preserves explicit sni in generated tls`() {
        val vless = profile.copy(
            protocol = Protocol.Vless,
            uuid = "96b0101c-0eb9-4089-9a00-111122223333",
            security = "reality",
            sni = "front.example.net",
            publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            shortId = "0123abcd",
        )
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(vless))))
        val tls = root.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")

        assertEquals("front.example.net", tls.getString("server_name"))
    }

    @Test
    fun `vless preserves explicit alpn and packet encoding`() {
        val vless = profile.copy(
            protocol = Protocol.Vless,
            uuid = "96b0101c-0eb9-4089-9a00-111122223333",
            security = "tls",
            alpn = listOf("h2", "http/1.1"),
            packetEncoding = "packetaddr",
        )
        val outbound = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(vless))))
            .getJSONArray("outbounds")
            .getJSONObject(0)

        assertEquals("packetaddr", outbound.getString("packet_encoding"))
        assertEquals("h2", outbound.getJSONObject("tls").getJSONArray("alpn").getString(0))
        assertEquals("http/1.1", outbound.getJSONObject("tls").getJSONArray("alpn").getString(1))
    }

    @Test
    fun `hysteria2 preserves explicit alpn instead of forcing h3`() {
        val explicitAlpn = profile.copy(alpn = listOf("h3", "hy2"))
        val tls = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(explicitAlpn))))
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("tls")

        assertEquals("h3", tls.getJSONArray("alpn").getString(0))
        assertEquals("hy2", tls.getJSONArray("alpn").getString(1))
    }

    @Test
    fun `route lets sing-box detect the physical interface`() {
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(profile))))

        assertTrue(root.getJSONObject("route").getBoolean("auto_detect_interface"))
    }

    @Test
    fun `remote dns uses doh through the active tunnel`() {
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(profile))))
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        val remote = (0 until servers.length())
            .map(servers::getJSONObject)
            .single { it.optString("tag") == "remote" }

        assertEquals("https", remote.getString("type"))
        assertEquals("1.1.1.1", remote.getString("server"))
        assertEquals(443, remote.getInt("server_port"))
        assertEquals("/dns-query", remote.getString("path"))
        assertEquals("cloudflare-dns.com", remote.getJSONObject("tls").getString("server_name"))
        assertEquals("proxy", remote.getString("detour"))
    }

    @Test
    fun `tun keeps the dual stack address`() {
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(profile))))
        val address = root.getJSONArray("inbounds").getJSONObject(0).getJSONArray("address")

        assertEquals(2, address.length())
        assertEquals("172.19.0.1/30", address.getString(0))
        assertEquals("fdfe:dcba:9876::1/126", address.getString(1))
    }

    @Test
    fun `hysteria2 keeps explicit port hopping options`() {
        val hoppingProfile = profile.copy(
            hysteria2ServerPorts = "20000-30000",
            hysteria2HopInterval = "5s",
            hysteria2HopIntervalMax = "10s",
        )
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(hoppingProfile))))
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)

        assertFalse(outbound.has("server_port"))
        assertEquals("20000-30000", outbound.getJSONArray("server_ports").getString(0))
        assertEquals("5s", outbound.getString("hop_interval"))
        assertEquals("10s", outbound.getString("hop_interval_max"))
    }

    @Test
    fun `trojan reality keeps reality tls parameters`() {
        val trojan = VpnProfile(
            name = "Trojan Fixture",
            protocol = Protocol.Trojan,
            server = "203.0.113.10",
            port = 8444,
            password = "00000000-0000-4000-8000-000000000001",
            security = "reality",
            sni = "example.com",
            publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            shortId = "0123abcd",
            transport = "tcp",
        )
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(trojan))))
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        val tls = outbound.getJSONObject("tls")
        val reality = tls.getJSONObject("reality")

        assertEquals("trojan", outbound.getString("type"))
        assertEquals("example.com", tls.getString("server_name"))
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
        assertTrue(reality.getBoolean("enabled"))
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", reality.getString("public_key"))
        assertEquals("0123abcd", reality.getString("short_id"))
    }

    @Test
    fun `vless xhttp preserves explicitly selected stream up mode`() {
        val xhttp = profile.copy(
            protocol = Protocol.Vless,
            uuid = "96b0101c-0eb9-4089-9a00-111122223333",
            transport = "xhttp",
            xhttpMode = "stream-up",
            path = "/warpy",
            host = "cdn.example.com",
        )
        val root = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(xhttp))))
        val transport = root
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("transport")

        assertEquals("xhttp", transport.getString("type"))
        assertEquals("stream-up", transport.getString("mode"))
        assertEquals("/warpy", transport.getString("path"))
        assertEquals("cdn.example.com", transport.getString("host"))
    }

    @Test
    fun `vmess http upgrade transport is emitted without falling back to tcp`() {
        val vmess = profile.copy(
            protocol = Protocol.Vmess,
            uuid = "96b0101c-0eb9-4089-9a00-111122223333",
            transport = "httpupgrade",
            path = "/upgrade",
            host = "cdn.example.com",
        )
        val outbound = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(vmess))))
            .getJSONArray("outbounds")
            .getJSONObject(0)
        val transport = outbound.getJSONObject("transport")

        assertEquals("httpupgrade", transport.getString("type"))
        assertEquals("/upgrade", transport.getString("path"))
        assertEquals("cdn.example.com", transport.getString("host"))
    }

    @Test
    fun `trojan xhttp transport reaches sing box unchanged`() {
        val trojan = profile.copy(
            protocol = Protocol.Trojan,
            password = "secret",
            transport = "xhttp",
            xhttpMode = "packet-up",
            path = "/tunnel",
            host = "cdn.example.com",
        )
        val transport = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(trojan))))
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("transport")

        assertEquals("xhttp", transport.getString("type"))
        assertEquals("packet-up", transport.getString("mode"))
        assertEquals("/tunnel", transport.getString("path"))
        assertEquals("cdn.example.com", transport.getString("host"))
    }

    @Test
    fun `shadowsocks plugin settings reach sing box unchanged`() {
        val shadowsocks = profile.copy(
            protocol = Protocol.Shadowsocks,
            password = "secret",
            encryption = "aes-256-gcm",
            plugin = "v2ray-plugin",
            pluginOptions = "tls;host=cdn.example.com;path=/ws",
        )
        val outbound = JSONObject(SingBoxConfigBuilder.build(AppSettings(profiles = listOf(shadowsocks))))
            .getJSONArray("outbounds")
            .getJSONObject(0)

        assertEquals("v2ray-plugin", outbound.getString("plugin"))
        assertEquals("tls;host=cdn.example.com;path=/ws", outbound.getString("plugin_opts"))
    }

    @Test
    fun `russian domains bypass the tunnel and use local dns`() {
        for (protocol in listOf(Protocol.Vless, Protocol.Hysteria2)) {
            val currentProfile = profile.copy(
                protocol = protocol,
                uuid = if (protocol == Protocol.Vless) "00000000-0000-4000-8000-000000000001" else "",
            )
            val root = JSONObject(
                SingBoxConfigBuilder.build(
                    AppSettings(
                        profiles = listOf(currentProfile),
                        adBlockEnabled = true,
                        blockQuic = true,
                    ),
                    filesDir = "/tmp",
                ),
            )
            val expected = setOf(".ru", ".xn--p1ai", ".su", "ozonusercontent.com")
            val dnsRules = root.getJSONObject("dns").getJSONArray("rules")
            val localDnsRule = (0 until dnsRules.length())
                .map(dnsRules::getJSONObject)
                .single { it.optString("server") == "local" }
            val dnsSuffixes = localDnsRule.getJSONArray("domain_suffix")
            val dnsValues = (0 until dnsSuffixes.length()).map(dnsSuffixes::getString).toSet()

            val routeRules = root.getJSONObject("route").getJSONArray("rules")
            val rules = (0 until routeRules.length()).map(routeRules::getJSONObject)
            val directIndex = rules.indexOfFirst {
                it.optString("outbound") == "direct" && it.has("domain_suffix")
            }
            val quicIndex = rules.indexOfFirst {
                it.optString("network") == "udp" && it.optInt("port") == 443
            }
            val adIndex = rules.indexOfFirst { it.optString("outbound") == "block" && it.has("rule_set") }
            val routeSuffixes = rules[directIndex].getJSONArray("domain_suffix")
            val routeValues = (0 until routeSuffixes.length()).map(routeSuffixes::getString).toSet()

            assertEquals(expected, dnsValues)
            assertEquals(expected, routeValues)
            assertTrue(adIndex in 0 until directIndex)
            assertTrue(quicIndex > directIndex)
        }
    }

    @Test
    fun `excluded websites bypass the tunnel without changing the default route`() {
        val root = JSONObject(
            SingBoxConfigBuilder.build(
                AppSettings(
                    profiles = listOf(profile),
                    siteTunnelMode = AppTunnelMode.Exclude,
                    tunneledSites = setOf("example.com", "media.example.org"),
                ),
            ),
        )
        val route = root.getJSONObject("route")
        val rules = route.getJSONArray("rules")
        val siteRule = (0 until rules.length())
            .map(rules::getJSONObject)
            .single { rule ->
                rule.optString("outbound") == "direct" &&
                    rule.optJSONArray("domain_suffix")?.let { suffixes ->
                        (0 until suffixes.length()).map(suffixes::getString).toSet() ==
                            setOf("example.com", "media.example.org")
                    } == true
            }

        assertEquals("direct", siteRule.getString("outbound"))
        assertEquals("proxy", route.getString("final"))
    }

    @Test
    fun `included websites use the tunnel with a direct default route`() {
        val root = JSONObject(
            SingBoxConfigBuilder.build(
                AppSettings(
                    profiles = listOf(profile),
                    siteTunnelMode = AppTunnelMode.Include,
                    tunneledSites = setOf("example.com"),
                ),
            ),
        )
        val route = root.getJSONObject("route")
        val rules = route.getJSONArray("rules")
        val siteRule = (0 until rules.length())
            .map(rules::getJSONObject)
            .single { rule ->
                rule.optString("outbound") == "proxy" && rule.has("domain_suffix")
            }

        assertEquals("example.com", siteRule.getJSONArray("domain_suffix").getString(0))
        assertEquals("direct", route.getString("final"))
    }
}

private fun org.json.JSONArray.hasQuicBlock(): Boolean = (0 until length())
    .map(::getJSONObject)
    .any {
        it.optString("network") == "udp" &&
            it.optInt("port") == 443 &&
            it.optString("outbound") == "block"
    }
