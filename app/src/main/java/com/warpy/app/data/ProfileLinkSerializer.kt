package com.warpy.app.data

import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

object ProfileLinkSerializer {
    fun serialize(profile: VpnProfile): Result<String> = runCatching {
        if (profile.raw.isNotBlank()) return@runCatching profile.raw.trim()
        require(profile.server.isNotBlank()) { "VPN server is missing" }
        require(profile.port in 1..65535) { "VPN port is invalid" }

        when (profile.protocol) {
            Protocol.Vless -> vless(profile)
            Protocol.Hysteria2 -> hysteria2(profile)
            Protocol.Trojan -> trojan(profile)
            Protocol.Vmess -> vmess(profile)
            Protocol.Shadowsocks -> shadowsocks(profile)
            Protocol.Socks -> socks(profile)
            Protocol.WireGuard -> wireGuard(profile)
            Protocol.Tuic -> tuic(profile)
            Protocol.Hysteria -> hysteria(profile)
        }
    }

    private fun vless(profile: VpnProfile): String {
        require(profile.uuid.isNotBlank()) { "VLESS UUID is missing" }
        return uri(
            scheme = "vless",
            userInfo = encode(profile.uuid),
            profile = profile,
            query = commonV2RayQuery(profile) + listOf(
                "flow" to profile.flow,
                "packetEncoding" to profile.packetEncoding,
            ),
        )
    }

    private fun hysteria2(profile: VpnProfile): String {
        require(profile.password.isNotBlank()) { "Hysteria2 password is missing" }
        return uri(
            scheme = "hysteria2",
            userInfo = encode(profile.password),
            profile = profile,
            query = listOf(
                "sni" to profile.sni,
                "alpn" to profile.alpn.joinToString(","),
                "insecure" to profile.allowInsecure.asQueryFlag(),
                "obfs" to profile.hysteria2ObfsType,
                "obfs-password" to profile.hysteria2ObfsPassword,
                "server_ports" to profile.hysteria2ServerPorts,
                "hop_interval" to profile.hysteria2HopInterval,
                "hop_interval_max" to profile.hysteria2HopIntervalMax,
                "up_mbps" to profile.hysteria2UpMbps.positiveString(),
                "down_mbps" to profile.hysteria2DownMbps.positiveString(),
            ),
        )
    }

    private fun trojan(profile: VpnProfile): String {
        require(profile.password.isNotBlank()) { "Trojan password is missing" }
        return uri(
            scheme = "trojan",
            userInfo = encode(profile.password),
            profile = profile,
            query = commonV2RayQuery(profile) + listOf(
                "flow" to profile.flow,
                "insecure" to profile.allowInsecure.asQueryFlag(),
            ),
        )
    }

    private fun vmess(profile: VpnProfile): String {
        require(profile.uuid.isNotBlank()) { "VMess UUID is missing" }
        val transport = profile.transport.ifBlank { "tcp" }
        val json = JSONObject()
            .put("v", "2")
            .put("ps", profile.displayName())
            .put("add", profile.server)
            .put("port", profile.port.toString())
            .put("id", profile.uuid)
            .put("aid", profile.alterId.toString())
            .put("scy", profile.encryption.ifBlank { "auto" })
            .put("net", transport)
            .put("host", profile.host)
            .put("path", if (transport == "grpc") profile.serviceName else profile.path)
            .put("tls", profile.security)
            .put("sni", profile.sni)
            .put("fp", profile.fingerprint)
            .put("alpn", profile.alpn.joinToString(","))
            .put("mode", profile.xhttpMode)
            .put("packetEncoding", profile.packetEncoding)
        val payload = Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        return "vmess://$payload"
    }

    private fun shadowsocks(profile: VpnProfile): String {
        require(profile.encryption.isNotBlank()) { "Shadowsocks method is missing" }
        require(profile.password.isNotBlank()) { "Shadowsocks password is missing" }
        val credentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${profile.encryption}:${profile.password}".toByteArray(Charsets.UTF_8))
        val plugin = if (profile.plugin.isBlank()) "" else listOf(
            "plugin" to listOf(profile.plugin, profile.pluginOptions)
                .filter(String::isNotBlank)
                .joinToString(";"),
        ).toQuery()
        return "ss://$credentials@${profile.endpoint()}$plugin#${encode(profile.displayName())}"
    }

    private fun socks(profile: VpnProfile): String {
        val credentials = when {
            profile.username.isBlank() && profile.password.isBlank() -> ""
            else -> "${encode(profile.username)}:${encode(profile.password)}@"
        }
        return "socks5://$credentials${profile.endpoint()}#${encode(profile.displayName())}"
    }

    private fun wireGuard(profile: VpnProfile): String {
        require(profile.privateKey.isNotBlank()) { "WireGuard private key is missing" }
        require(profile.peerPublicKey.isNotBlank()) { "WireGuard peer public key is missing" }
        require(profile.localAddress.isNotBlank()) { "WireGuard local address is missing" }
        return uri(
            scheme = "wireguard",
            userInfo = "",
            profile = profile,
            query = listOf(
                "pk" to profile.privateKey,
                "peer_pk" to profile.peerPublicKey,
                "pre_shared_key" to profile.preSharedKey,
                "local_address" to profile.localAddress,
                "reserved" to profile.reserved,
                "mtu" to profile.mtu.positiveString(),
            ),
        )
    }

    private fun tuic(profile: VpnProfile): String {
        require(profile.uuid.isNotBlank()) { "TUIC UUID is missing" }
        val credentials = listOf(profile.uuid, profile.password).joinToString(":", transform = ::encode)
        return uri(
            scheme = "tuic",
            userInfo = credentials,
            profile = profile,
            query = listOf(
                "sni" to profile.sni,
                "alpn" to profile.alpn.joinToString(","),
                "insecure" to profile.allowInsecure.asQueryFlag(),
                "congestion_control" to profile.congestionControl,
                "udp_relay_mode" to profile.udpRelayMode,
            ),
        )
    }

    private fun hysteria(profile: VpnProfile): String = uri(
        scheme = "hysteria",
        userInfo = "",
        profile = profile,
        query = listOf(
            "auth" to profile.password,
            "sni" to profile.sni,
            "alpn" to profile.alpn.joinToString(","),
            "insecure" to profile.allowInsecure.asQueryFlag(),
            "obfs" to profile.hysteria2ObfsPassword,
            "upmbps" to profile.hysteria2UpMbps.positiveString(),
            "downmbps" to profile.hysteria2DownMbps.positiveString(),
        ),
    )

    private fun commonV2RayQuery(profile: VpnProfile): List<Pair<String, String>> = listOf(
        "security" to profile.security,
        "sni" to profile.sni,
        "pbk" to profile.publicKey,
        "sid" to profile.shortId,
        "fp" to profile.fingerprint,
        "alpn" to profile.alpn.joinToString(","),
        "type" to profile.transport,
        "host" to profile.host,
        "path" to profile.path,
        "serviceName" to profile.serviceName,
        "mode" to profile.xhttpMode,
        "mux" to profile.multiplex.asQueryFlag(),
    )

    private fun uri(
        scheme: String,
        userInfo: String,
        profile: VpnProfile,
        query: List<Pair<String, String>>,
    ): String {
        val authority = if (userInfo.isBlank()) profile.endpoint() else "$userInfo@${profile.endpoint()}"
        return "$scheme://$authority${query.toQuery()}#${encode(profile.displayName())}"
    }

    private fun List<Pair<String, String>>.toQuery(): String {
        val value = filter { (_, item) -> item.isNotBlank() }
            .joinToString("&") { (key, item) -> "${encode(key)}=${encode(item)}" }
        return if (value.isBlank()) "" else "?$value"
    }

    private fun VpnProfile.endpoint(): String {
        val host = if (server.contains(':') && !server.startsWith('[')) "[$server]" else server
        return "$host:$port"
    }

    private fun VpnProfile.displayName(): String = name.ifBlank { sni.ifBlank { server } }

    private fun Boolean.asQueryFlag(): String = if (this) "1" else ""

    private fun Int.positiveString(): String = takeIf { it > 0 }?.toString().orEmpty()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
}
