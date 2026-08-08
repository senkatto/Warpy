package com.warpy.app.vpn

import com.warpy.app.BuildConfig
import com.warpy.app.model.AppSettings
import com.warpy.app.model.AppTunnelMode
import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import com.warpy.app.vpn.generated.CoreContract
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigBuilder {
    private const val AD_RULE_SET_TAG = "warpy-ads"

    fun build(
        settings: AppSettings,
        dynamicMtu: Int = CoreContract.Android.defaultMtu,
        filesDir: String = "",
        localProxy: LocalProxyConfig? = null,
    ): String {
        val inbounds = JSONArray().put(tunInbound(settings, dynamicMtu))
        localProxy?.let { inbounds.put(localHealthProxyInbound(it)) }

        val outbounds = JSONArray()
        val outboundTags = JSONArray()
        val endpoints = JSONArray()

        settings.profiles.forEachIndexed { index, prof ->
            val tag = "profile_$index"
            if (prof.protocol == Protocol.WireGuard) {
                endpoints.put(prof.toWireGuardEndpoint(tag))
            } else {
                outbounds.put(prof.toOutbound(tag))
            }
            outboundTags.put(tag)
        }

        outbounds.put(
            JSONObject()
                .put("type", "selector")
                .put("tag", CoreContract.Tags.proxy)
                .put("outbounds", outboundTags)
                .put("interrupt_exist_connections", true)
        )

        outbounds.put(JSONObject(mapOf("type" to "direct", "tag" to CoreContract.Tags.direct)))
        outbounds.put(JSONObject(mapOf("type" to "block", "tag" to CoreContract.Tags.block)))

        return JSONObject()
            .put("log", JSONObject(mapOf("level" to if (BuildConfig.DEBUG) "debug" else "warn")))
            .put("dns", dns(settings))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .apply { if (endpoints.length() > 0) put("endpoints", endpoints) }
            .put("route", route(settings, filesDir))
            .toString(2)
    }

    private fun tunInbound(
        settings: AppSettings,
        dynamicMtu: Int = CoreContract.Android.defaultMtu,
    ): JSONObject = JSONObject()
        .put("type", "tun")
        .put("tag", CoreContract.Tags.tun)
        .put("interface_name", CoreContract.Android.interfaceName)
        .put("address", CoreContract.Android.addresses.toJsonArray())
        .put("mtu", if (settings.mtu <= 0) dynamicMtu else settings.mtu.coerceIn(1280, 1500))
        .put("auto_route", true)
        .put("strict_route", CoreContract.Android.strictRoute)
        .put("stack", CoreContract.Android.stack)
        .put("sniff", true)
        .put("sniff_override_destination", true)
        .apply {
            val packages = settings.tunneledApps
                .filter(String::isNotBlank)
                .fold(JSONArray()) { array, packageName -> array.put(packageName) }
            when {
                packages.length() == 0 -> Unit
                settings.appTunnelMode == AppTunnelMode.Include -> put("include_package", packages)
                settings.appTunnelMode == AppTunnelMode.Exclude -> put("exclude_package", packages)
                else -> Unit
            }
        }

    private fun localHealthProxyInbound(config: LocalProxyConfig): JSONObject = JSONObject()
        .put("type", "mixed")
        .put("tag", "health-proxy-in")
        .put("listen", "127.0.0.1")
        .put("listen_port", config.port)
        .put(
            "users",
            JSONArray().put(
                JSONObject()
                    .put("username", config.username)
                    .put("password", config.password),
            ),
        )

    private fun dns(settings: AppSettings): JSONObject {
        val rules = JSONArray()
        val remoteServer = JSONObject()
            .put("type", "https")
            .put("tag", CoreContract.Tags.remoteDns)
            .put("server", CoreContract.Dns.remoteServer)
            .put("server_port", CoreContract.Dns.remotePort)
            .put("path", CoreContract.Dns.remotePath)
            .put(
                "tls",
                JSONObject()
                    .put("enabled", true)
                    .put("server_name", CoreContract.Dns.remoteTlsServerName),
            )
            .put("detour", CoreContract.Tags.proxy)
        if (settings.adBlockEnabled) {
            rules.put(
                JSONObject()
                    .put("rule_set", JSONArray().put(AD_RULE_SET_TAG))
                    .put("server", CoreContract.Tags.block)
            )
        }
        rules.put(
            JSONObject()
                .put("domain_suffix", russianDomainSuffixes())
                .put("server", CoreContract.Android.localDnsTag)
        )

        return JSONObject()
            .put(
                "servers",
                JSONArray()
                    .put(remoteServer)
                    .put(
                        JSONObject()
                            .put("type", CoreContract.Android.localDnsType)
                            .put("tag", CoreContract.Android.localDnsTag),
                    )
                    .put(JSONObject(mapOf("tag" to CoreContract.Tags.block, "address" to "rcode://success")))
            )
            .put("rules", rules)
            .put("final", CoreContract.Tags.remoteDns)
            .put("strategy", CoreContract.Dns.strategy)
    }

    private fun route(settings: AppSettings, filesDir: String): JSONObject {
        val rules = JSONArray()
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        rules.put(
            JSONObject()
                .put("ip_cidr", JSONArray().put("::/0"))
                .put("outbound", CoreContract.Tags.block),
        )
        if (settings.bypassLan) {
            rules.put(JSONObject().put("ip_is_private", true).put("outbound", CoreContract.Tags.direct))
        }
        if (settings.adBlockEnabled) {
            rules.put(
                JSONObject()
                    .put("rule_set", JSONArray().put(AD_RULE_SET_TAG))
                    .put("outbound", CoreContract.Tags.block),
            )
        }
        rules.put(
            JSONObject()
                .put("domain_suffix", russianDomainSuffixes())
                .put("outbound", CoreContract.Tags.direct),
        )
        val tunneledSites = settings.tunneledSites
            .filter(String::isNotBlank)
            .sorted()
            .toJsonArray()
        if (tunneledSites.length() > 0) {
            when (settings.siteTunnelMode) {
                AppTunnelMode.Include -> rules.put(
                    JSONObject()
                        .put("domain_suffix", tunneledSites)
                        .put("outbound", CoreContract.Tags.proxy),
                )
                AppTunnelMode.Exclude -> rules.put(
                    JSONObject()
                        .put("domain_suffix", tunneledSites)
                        .put("outbound", CoreContract.Tags.direct),
                )
                AppTunnelMode.All -> Unit
            }
        }
        val blockQuic = settings.blockQuic
        if (!CoreContract.Routing.blockQuicOnlyWhenEnabled || blockQuic) {
            rules.put(
                JSONObject()
                    .put("network", "udp")
                    .put("port", 443)
                    .put("outbound", CoreContract.Tags.block),
            )
        }

        return JSONObject()
            .put("rules", rules)
            .apply {
                val ruleSets = ruleSets(settings, filesDir)
                if (ruleSets.length() > 0) put("rule_set", ruleSets)
            }
            .put(
                "final",
                if (settings.siteTunnelMode == AppTunnelMode.Include) {
                    CoreContract.Tags.direct
                } else {
                    CoreContract.Tags.proxy
                },
            )
            .put("auto_detect_interface", true)
    }

    private fun ruleSets(settings: AppSettings, filesDir: String): JSONArray {
        val ruleSets = JSONArray()
        if (settings.adBlockEnabled && filesDir.isNotBlank()) {
            ruleSets.put(
                JSONObject()
                    .put("type", "local")
                    .put("format", "source")
                    .put("tag", AD_RULE_SET_TAG)
                    .put("path", "$filesDir/warpy-ads.json")
            )
        }
        return ruleSets
    }

    private fun russianDomainSuffixes() = CoreContract.Routing.russianDomainSuffixes.toJsonArray()

    private fun VpnProfile.toOutbound(customTag: String = "profile"): JSONObject = when (protocol) {
        Protocol.Vless -> JSONObject()
            .put("type", "vless")
            .put("tag", customTag)
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("flow", flow.takeIf { it.isNotBlank() })
            .put("packet_encoding", packetEncoding.ifBlank { "xudp" })
            .put("tls", tlsForVless())
            .apply {
                if (multiplex) {
                    put("multiplex", JSONObject().put("enabled", true))
                }
                transportForProfile(transport, path, host, serviceName, xhttpMode)?.let {
                    put("transport", it)
                }
            }

        Protocol.Trojan -> JSONObject()
            .put("type", "trojan")
            .put("tag", customTag)
            .put("server", server)
            .put("server_port", port)
            .put("password", password)
            .put("tls", tlsForTrojan())
            .apply {
                if (multiplex) {
                    put("multiplex", JSONObject().put("enabled", true))
                }
                transportForProfile(transport, path, host, serviceName, xhttpMode)?.let {
                    put("transport", it)
                }
            }


        Protocol.Hysteria2 -> JSONObject()
            .put("type", "hysteria2")
            .put("tag", customTag)
            .put("server", server)
            .put("password", password)
            .put("tls", tlsForHysteria2())
            .apply {
                if (hysteria2ServerPorts.isBlank()) {
                    put("server_port", port)
                } else {
                    put("server_ports", listJson(hysteria2ServerPorts))
                }
                if (hysteria2ServerPorts.isNotBlank() || hysteria2HopInterval.isNotBlank()) {
                    put("hop_interval", hysteria2HopInterval.ifBlank { "10s" })
                    if (hysteria2HopIntervalMax.isNotBlank()) put("hop_interval_max", hysteria2HopIntervalMax)
                }
                if (hysteria2UpMbps > 0) put("up_mbps", hysteria2UpMbps)
                if (hysteria2DownMbps > 0) put("down_mbps", hysteria2DownMbps)

                if (hysteria2ObfsType.isNotBlank() && hysteria2ObfsPassword.isNotBlank()) {
                    put(
                        "obfs",
                        JSONObject()
                            .put("type", hysteria2ObfsType)
                            .put("password", hysteria2ObfsPassword)
                    )
                }
            }

        Protocol.Vmess -> JSONObject()
            .put("type", "vmess")
            .put("tag", customTag)
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("security", encryption.ifBlank { "auto" })
            .put("alter_id", alterId)
            .put("packet_encoding", packetEncoding.takeIf { it.isNotBlank() })
            .put("tls", tlsForGeneric())
            .apply {
                transportForProfile(transport, path, host, serviceName, xhttpMode)?.let {
                    put("transport", it)
                }
            }

        Protocol.Shadowsocks -> JSONObject()
            .put("type", "shadowsocks")
            .put("tag", customTag)
            .put("server", server)
            .put("server_port", port)
            .put("method", encryption)
            .put("password", password)
            .apply {
                if (plugin.isNotBlank()) put("plugin", plugin)
                if (pluginOptions.isNotBlank()) put("plugin_opts", pluginOptions)
            }

        Protocol.Socks -> JSONObject()
            .put("type", "socks")
            .put("tag", customTag)
            .put("server", server)
            .put("server_port", port)
            .put("version", "5")
            .put("username", username.takeIf { it.isNotBlank() })
            .put("password", password.takeIf { it.isNotBlank() })

        Protocol.WireGuard -> error("WireGuard must be configured as an endpoint")

        Protocol.Tuic -> JSONObject()
            .put("type", "tuic")
            .put("tag", customTag)
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("password", password)
            .put("congestion_control", congestionControl.ifBlank { "cubic" })
            .put("udp_relay_mode", udpRelayMode.ifBlank { "native" })
            .put("tls", tlsForGeneric(required = true))

        Protocol.Hysteria -> JSONObject()
            .put("type", "hysteria")
            .put("tag", customTag)
            .put("server", server)
            .put("server_port", port)
            .put("auth_str", password.takeIf { it.isNotBlank() })
            .put("obfs", hysteria2ObfsPassword.takeIf { it.isNotBlank() })
            .put("up_mbps", hysteria2UpMbps.takeIf { it > 0 })
            .put("down_mbps", hysteria2DownMbps.takeIf { it > 0 })
            .put("tls", tlsForGeneric(required = true))
    }

    private fun VpnProfile.toWireGuardEndpoint(customTag: String): JSONObject {
        val peer = JSONObject()
            .put("address", server)
            .put("port", port)
            .put("public_key", peerPublicKey)
            .put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
            .put("pre_shared_key", preSharedKey.takeIf { it.isNotBlank() })
            .put("reserved", reservedBytes(reserved))
        return JSONObject()
            .put("type", "wireguard")
            .put("tag", customTag)
            .put("address", splitValues(localAddress))
            .put("private_key", privateKey)
            .put("peers", JSONArray().put(peer))
            .put("mtu", mtu.takeIf { it > 0 })
    }

    private fun VpnProfile.tlsForGeneric(required: Boolean = false): JSONObject? {
        val enabled = required || security.equals("tls", true)
        if (!enabled) return null
        return JSONObject()
            .put("enabled", true)
            .put("server_name", sni.ifBlank { server })
            .put("insecure", allowInsecure)
            .put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
            .apply {
                if (alpn.isNotEmpty()) put("alpn", alpn.toJsonArray())
            }
    }

    private fun splitValues(value: String): JSONArray = value
        .split(',', ';')
        .map(String::trim)
        .filter(String::isNotBlank)
        .fold(JSONArray()) { array, item -> array.put(item) }

    private fun reservedBytes(value: String): JSONArray? {
        if (value.isBlank()) return null
        val bytes = value.split(',', ';')
            .mapNotNull { it.trim().toIntOrNull()?.takeIf { byte -> byte in 0..255 } }
        return bytes.takeIf { it.size == 3 }?.fold(JSONArray()) { array, byte -> array.put(byte) }
    }

    private fun VpnProfile.tlsForVless(): JSONObject {
        val tls = JSONObject()
            .put("enabled", security.equals("tls", true) || security.equals("reality", true))
            .put("server_name", sni.ifBlank { server })
            .put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))

        if (alpn.isNotEmpty()) tls.put("alpn", alpn.toJsonArray())

        if (security.equals("reality", true)) {
            tls.put(
                "reality",
                JSONObject()
                    .put("enabled", true)
                    .put("public_key", publicKey)
                    .put("short_id", shortId)
            )
        }
        return tls
    }

    private fun VpnProfile.tlsForHysteria2(): JSONObject = JSONObject()
        .put("enabled", true)
        .put("server_name", sni.ifBlank { server })
        .put("insecure", allowInsecure)
        .put("alpn", (alpn.ifEmpty { listOf("h3") }).toJsonArray())

    private fun VpnProfile.tlsForTrojan(): JSONObject = JSONObject()
        .put("enabled", true)
        .put("server_name", sni.ifBlank { server })
        .put("insecure", allowInsecure)
        .put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
        .apply {
            if (alpn.isNotEmpty()) put("alpn", alpn.toJsonArray())
            if (security.equals("reality", true)) {
                put(
                    "reality",
                    JSONObject()
                        .put("enabled", true)
                        .put("public_key", publicKey)
                        .put("short_id", shortId),
                )
            }
        }

    private fun transportForProfile(
        transport: String,
        path: String,
        host: String,
        serviceName: String,
        xhttpMode: String,
    ): JSONObject? {
        if (transport.isBlank()) return null
        val normalizedTransport = when (val value = transport.trim().lowercase()) {
            "h2" -> "http"
            "http-upgrade", "http_upgrade" -> "httpupgrade"
            "splithttp", "split-http", "split_http" -> "xhttp"
            else -> value
        }
        return when (normalizedTransport) {
            "tcp", "raw" -> null
            "ws" -> JSONObject()
                .put("type", "ws")
                .put("path", path.ifBlank { "/" })
                .apply {
                    if (host.isNotBlank()) {
                        put("headers", JSONObject().put("Host", host))
                    }
                }
            "grpc" -> JSONObject()
                .put("type", "grpc")
                .put("service_name", serviceName)
            "xhttp" -> JSONObject()
                .put("type", "xhttp")
                .put("mode", xhttpMode.ifBlank { CoreContract.Android.xhttpDefaultMode }.lowercase())
                .put("path", path.ifBlank { "/" })
                .apply {
                    if (host.isNotBlank()) {
                        put("host", host)
                    }
                }
            "http" -> JSONObject()
                .put("type", "http")
                .put("path", path.ifBlank { "/" })
                .apply {
                    if (host.isNotBlank()) put("host", JSONArray().put(host))
                }
            "httpupgrade" -> JSONObject()
                .put("type", "httpupgrade")
                .put("path", path.ifBlank { "/" })
                .apply {
                    if (host.isNotBlank()) put("host", host)
                }
            else -> throw IllegalArgumentException("Unsupported transport: $transport")
        }
    }

    private fun Iterable<String>.toJsonArray(): JSONArray =
        fold(JSONArray()) { array, value -> array.put(value) }

    private fun listJson(value: String): JSONArray =
        value.split(',', ';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .fold(JSONArray()) { array, item -> array.put(item) }
}
