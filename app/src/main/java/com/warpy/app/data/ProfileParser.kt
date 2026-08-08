package com.warpy.app.data

import android.net.Uri
import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import org.json.JSONObject
import java.net.URI
import java.util.Base64

object ProfileParser {
    fun parse(raw: String): Result<VpnProfile> = runCatching {
        val value = extractProfileLink(raw)
        require(value.isNotBlank()) { "Paste a VPN profile link" }

        when {
            value.startsWith("vless://", ignoreCase = true) -> parseVless(value)
            value.startsWith("hysteria2://", ignoreCase = true) || value.startsWith("hy2://", ignoreCase = true) ->
                parseHysteria2(value)
            value.startsWith("trojan://", ignoreCase = true) -> parseTrojan(value)
            value.startsWith("vmess://", ignoreCase = true) -> parseVmess(value)
            value.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(value)
            value.startsWith("socks://", ignoreCase = true) || value.startsWith("socks5://", ignoreCase = true) ->
                parseSocks(value)
            value.startsWith("wg://", ignoreCase = true) || value.startsWith("wireguard://", ignoreCase = true) ->
                parseWireGuard(value)
            value.startsWith("tuic://", ignoreCase = true) -> parseTuic(value)
            value.startsWith("hysteria://", ignoreCase = true) -> parseHysteria(value)
            else -> error("Unsupported VPN profile link")
        }
    }

    private fun extractProfileLink(raw: String): String {
        val value = raw.trim()
        if (
            value.startsWith("vless://", ignoreCase = true) ||
            value.startsWith("hysteria2://", ignoreCase = true) ||
            value.startsWith("hy2://", ignoreCase = true) ||
            value.startsWith("trojan://", ignoreCase = true) ||
            value.startsWith("vmess://", ignoreCase = true) ||
            value.startsWith("ss://", ignoreCase = true) ||
            value.startsWith("socks://", ignoreCase = true) ||
            value.startsWith("socks5://", ignoreCase = true) ||
            value.startsWith("wg://", ignoreCase = true) ||
            value.startsWith("wireguard://", ignoreCase = true) ||
            value.startsWith("tuic://", ignoreCase = true) ||
            value.startsWith("hysteria://", ignoreCase = true)
        ) {
            return value.trimProfileTail()
        }

        val match = PROFILE_LINK_REGEX.find(value) ?: return value
        return match.value.trimProfileTail()
    }

    private fun parseVless(raw: String): VpnProfile {
        val uri = Uri.parse(raw)
        val server = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 443
        val name = decode(uri.fragment).ifBlank { server }

        require(server.isNotBlank()) { "VLESS server is missing" }
        require(uri.userInfo.orEmpty().isNotBlank()) { "VLESS UUID is missing" }

        val transport = normalizeTransport(
            uri.getQueryParameter("type")
                ?: uri.getQueryParameter("transport").orEmpty()
        )
        if (transport.isNotBlank() && transport !in VLESS_TRANSPORTS) {
            throw IllegalArgumentException("Профиль содержит пока неподдерживаемый транспорт: $transport")
        }
        val xhttpMode = uri.getQueryParameter("mode")
            .orEmpty()
            .lowercase()
            .ifBlank { DEFAULT_XHTTP_MODE }
        if (transport == "xhttp" && xhttpMode !in XHTTP_MODES) {
            throw IllegalArgumentException("Профиль содержит неподдерживаемый режим XHTTP: $xhttpMode")
        }

        return VpnProfile(
            name = name,
            protocol = Protocol.Vless,
            server = server,
            port = port,
            uuid = uri.userInfo.orEmpty(),
            security = uri.getQueryParameter("security").orEmpty(),
            sni = uri.getQueryParameter("sni").orEmpty(),
            flow = uri.getQueryParameter("flow").orEmpty(),
            publicKey = uri.getQueryParameter("pbk").orEmpty(),
            shortId = uri.getQueryParameter("sid").orEmpty(),
            fingerprint = uri.getQueryParameter("fp") ?: "chrome",
            alpn = parseAlpn(uri.getQueryParameter("alpn")),
            allowInsecure = uri.getQueryParameter("allowInsecure").isEnabledParameter() ||
                uri.getQueryParameter("allow_insecure").isEnabledParameter() ||
                uri.getQueryParameter("insecure").isEnabledParameter(),
            packetEncoding = uri.getQueryParameter("packetEncoding")
                ?: uri.getQueryParameter("packet_encoding").orEmpty(),
            transport = transport,
            host = uri.getQueryParameter("host").orEmpty(),
            path = uri.getQueryParameter("path").orEmpty(),
            serviceName = uri.getQueryParameter("serviceName")
                ?: uri.getQueryParameter("service_name")
                ?: uri.getQueryParameter("service-name").orEmpty(),
            xhttpMode = if (transport == "xhttp") xhttpMode else "",
            multiplex = uri.getQueryParameter("mux") == "1" || uri.getQueryParameter("multiplex") == "1",
            raw = raw,
        )
    }

    private fun parseHysteria2(raw: String): VpnProfile {
        val normalized = raw.replaceFirst("hy2://", "hysteria2://", ignoreCase = true)
        val uri = URI(normalized)
        val params = queryParams(uri.rawQuery.orEmpty())
        val server = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 443
        val name = decode(uri.rawFragment).ifBlank { server }

        require(server.isNotBlank()) { "Hysteria2 server is missing" }
        require(uri.rawUserInfo.orEmpty().isNotBlank()) { "Hysteria2 password is missing" }

        return VpnProfile(
            name = name,
            protocol = Protocol.Hysteria2,
            server = server,
            port = port,
            password = decodeSecret(uri.rawUserInfo),
            sni = params["sni"].orEmpty(),
            alpn = parseAlpn(params["alpn"]),
            allowInsecure = firstQuery(params, "allowInsecure", "allow_insecure", "insecure")
                .isEnabledParameter(),
            hysteria2ObfsType = firstQuery(params, "obfs", "obfs-type").ifBlank { if (params.containsKey("obfs-password") || params.containsKey("obfs_password")) "salamander" else "" },
            hysteria2ObfsPassword = firstQuery(params, "obfs-password", "obfs_password").replace(' ', '+'),
            hysteria2ServerPorts = firstQuery(params, "server_ports", "server-ports", "mport", "ports"),
            hysteria2HopInterval = firstQuery(params, "hop_interval", "hop-interval"),
            hysteria2HopIntervalMax = firstQuery(params, "hop_interval_max", "hop-interval-max"),
            hysteria2UpMbps = firstQuery(params, "up_mbps", "upmbps").toIntOrNull() ?: 0,
            hysteria2DownMbps = firstQuery(params, "down_mbps", "downmbps").toIntOrNull() ?: 0,
            raw = raw,
        )
    }

    private fun parseTrojan(raw: String): VpnProfile {
        val uri = Uri.parse(raw)
        val server = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 443
        val name = decode(uri.fragment).ifBlank { server }

        require(server.isNotBlank()) { "Trojan server is missing" }
        require(uri.userInfo.orEmpty().isNotBlank()) { "Trojan password is missing" }

        val transport = normalizeTransport(
            uri.getQueryParameter("type")
                ?: uri.getQueryParameter("transport").orEmpty()
        )
        if (transport.isNotBlank() && transport !in TROJAN_TRANSPORTS) {
            throw IllegalArgumentException("Профиль содержит пока неподдерживаемый транспорт: $transport")
        }
        val xhttpMode = uri.getQueryParameter("mode")
            .orEmpty()
            .lowercase()
            .ifBlank { DEFAULT_XHTTP_MODE }
        if (transport == "xhttp" && xhttpMode !in XHTTP_MODES) {
            throw IllegalArgumentException("Unsupported Trojan XHTTP mode: $xhttpMode")
        }

        return VpnProfile(
            name = name,
            protocol = Protocol.Trojan,
            server = server,
            port = port,
            password = decodeSecret(uri.userInfo),
            security = uri.getQueryParameter("security").orEmpty(),
            sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer").orEmpty(),
            flow = uri.getQueryParameter("flow").orEmpty(),
            publicKey = uri.getQueryParameter("pbk").orEmpty(),
            shortId = uri.getQueryParameter("sid").orEmpty(),
            fingerprint = uri.getQueryParameter("fp") ?: "chrome",
            alpn = parseAlpn(uri.getQueryParameter("alpn")),
            allowInsecure = uri.getQueryParameter("allowInsecure").isEnabledParameter() ||
                uri.getQueryParameter("allow_insecure").isEnabledParameter() ||
                uri.getQueryParameter("insecure").isEnabledParameter(),
            transport = transport,
            host = uri.getQueryParameter("host").orEmpty(),
            path = uri.getQueryParameter("path").orEmpty(),
            serviceName = uri.getQueryParameter("serviceName")
                ?: uri.getQueryParameter("service_name")
                ?: uri.getQueryParameter("service-name").orEmpty(),
            xhttpMode = if (transport == "xhttp") xhttpMode else "",
            multiplex = uri.getQueryParameter("mux") == "1" || uri.getQueryParameter("multiplex") == "1",
            raw = raw,
        )
    }

    private fun parseVmess(raw: String): VpnProfile {
        val payload = raw.substringAfter("://").substringBefore('#').trim()
        val json = JSONObject(decodeBase64(payload))
        val server = json.optString("add")
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port", 443)
        val uuid = json.optString("id")
        require(server.isNotBlank()) { "VMess server is missing" }
        require(uuid.isNotBlank()) { "VMess UUID is missing" }
        require(port in 1..65535) { "VMess port is invalid" }

        val transport = normalizeTransport(json.optString("net", "tcp"))
        require(transport in VMESS_TRANSPORTS) { "Unsupported VMess transport: $transport" }
        val xhttpMode = json.optString("mode")
            .lowercase()
            .ifBlank { DEFAULT_XHTTP_MODE }
        if (transport == "xhttp" && xhttpMode !in XHTTP_MODES) {
            throw IllegalArgumentException("Unsupported VMess XHTTP mode: $xhttpMode")
        }
        return VpnProfile(
            name = json.optString("ps").ifBlank { server },
            protocol = Protocol.Vmess,
            server = server,
            port = port,
            uuid = uuid,
            security = json.optString("tls"),
            sni = json.optString("sni"),
            fingerprint = json.optString("fp", "chrome"),
            alpn = parseAlpn(json.optString("alpn")),
            transport = transport,
            host = json.optString("host"),
            path = json.optString("path"),
            serviceName = json.optString("path").takeIf { transport == "grpc" }.orEmpty(),
            xhttpMode = if (transport == "xhttp") xhttpMode else "",
            encryption = json.optString("scy", "auto"),
            alterId = json.optString("aid").toIntOrNull() ?: json.optInt("aid", 0),
            packetEncoding = json.optString("packetEncoding"),
            raw = raw,
        )
    }

    private fun parseShadowsocks(raw: String): VpnProfile {
        val withoutFragment = raw.substringBefore('#')
        val name = decode(raw.substringAfter('#', ""))
        val body = withoutFragment.substringAfter("://")
        val normalized = if ('@' in body) {
            val userInfo = body.substringBeforeLast('@')
            val decodedUserInfo = if (':' in userInfo) decode(userInfo) else decodeBase64(userInfo)
            "$decodedUserInfo@${body.substringAfterLast('@')}"
        } else {
            decodeBase64(body)
        }
        val credential = normalized.substringBeforeLast('@')
        val endpoint = normalized.substringAfterLast('@')
        val separator = credential.indexOf(':')
        require(separator > 0) { "Shadowsocks method or password is missing" }
        val uri = URI("ss://${Uri.encode(credential.substring(0, separator))}:${Uri.encode(credential.substring(separator + 1))}@$endpoint")
        val server = uri.host.orEmpty()
        val port = uri.port
        val pluginSpec = firstQuery(queryParams(uri.rawQuery.orEmpty()), "plugin")
        val pluginParts = pluginSpec.split(';', limit = 2)
        val plugin = pluginParts.firstOrNull().orEmpty().trim()
        val pluginOptions = pluginParts.getOrNull(1).orEmpty()
        require(server.isNotBlank() && port in 1..65535) { "Shadowsocks server is invalid" }
        require(plugin.isBlank() || plugin in SHADOWSOCKS_PLUGINS) { "Unsupported Shadowsocks plugin: $plugin" }
        return VpnProfile(
            name = name.ifBlank { server },
            protocol = Protocol.Shadowsocks,
            server = server,
            port = port,
            password = credential.substring(separator + 1),
            encryption = credential.substring(0, separator),
            plugin = plugin,
            pluginOptions = pluginOptions,
            raw = raw,
        )
    }

    private fun parseSocks(raw: String): VpnProfile {
        val normalized = raw.replaceFirst("socks5://", "socks://", ignoreCase = true)
        val uri = URI(normalized)
        val server = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 1080
        val credentials = uri.rawUserInfo.orEmpty().split(':', limit = 2)
        require(server.isNotBlank()) { "SOCKS server is missing" }
        return VpnProfile(
            name = decode(uri.rawFragment).ifBlank { server },
            protocol = Protocol.Socks,
            server = server,
            port = port,
            username = decode(credentials.getOrNull(0)),
            password = decodeSecret(credentials.getOrNull(1)),
            raw = raw,
        )
    }

    private fun parseWireGuard(raw: String): VpnProfile {
        val normalized = raw.replaceFirst("wg://", "wireguard://", ignoreCase = true)
        val uri = URI(normalized)
        val params = queryParams(uri.rawQuery.orEmpty())
        val server = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 51820
        val privateKey = firstQuery(params, "pk", "private_key", "private-key")
        val peerPublicKey = firstQuery(params, "peer_pk", "public_key", "public-key")
        val localAddress = firstQuery(params, "local_address", "local-address", "address")
        require(server.isNotBlank()) { "WireGuard server is missing" }
        require(privateKey.isNotBlank()) { "WireGuard private key is missing" }
        require(peerPublicKey.isNotBlank()) { "WireGuard peer public key is missing" }
        require(localAddress.isNotBlank()) { "WireGuard local address is missing" }
        return VpnProfile(
            name = decode(uri.rawFragment).ifBlank { server },
            protocol = Protocol.WireGuard,
            server = server,
            port = port,
            privateKey = privateKey,
            peerPublicKey = peerPublicKey,
            preSharedKey = firstQuery(params, "pre_shared_key", "pre-shared-key", "psk"),
            localAddress = localAddress,
            reserved = firstQuery(params, "reserved"),
            mtu = firstQuery(params, "mtu").toIntOrNull() ?: 0,
            raw = raw,
        )
    }

    private fun parseTuic(raw: String): VpnProfile {
        val uri = URI(raw)
        val params = queryParams(uri.rawQuery.orEmpty())
        val credentials = uri.rawUserInfo.orEmpty().split(':', limit = 2)
        val server = uri.host.orEmpty()
        val uuid = decode(credentials.getOrNull(0))
        require(server.isNotBlank()) { "TUIC server is missing" }
        require(uuid.isNotBlank()) { "TUIC UUID is missing" }
        return VpnProfile(
            name = decode(uri.rawFragment).ifBlank { server },
            protocol = Protocol.Tuic,
            server = server,
            port = uri.port.takeIf { it > 0 } ?: 443,
            uuid = uuid,
            password = decodeSecret(credentials.getOrNull(1)),
            sni = firstQuery(params, "sni", "peer"),
            alpn = parseAlpn(params["alpn"]),
            allowInsecure = firstQuery(params, "allowInsecure", "allow_insecure", "insecure").isEnabledParameter(),
            congestionControl = firstQuery(params, "congestion_control", "congestion-control").ifBlank { "cubic" },
            udpRelayMode = firstQuery(params, "udp_relay_mode", "udp-relay-mode").ifBlank { "native" },
            raw = raw,
        )
    }

    private fun parseHysteria(raw: String): VpnProfile {
        val uri = URI(raw)
        val params = queryParams(uri.rawQuery.orEmpty())
        val server = uri.host.orEmpty()
        require(server.isNotBlank()) { "Hysteria server is missing" }
        return VpnProfile(
            name = decode(uri.rawFragment).ifBlank { server },
            protocol = Protocol.Hysteria,
            server = server,
            port = uri.port.takeIf { it > 0 } ?: 443,
            password = firstQuery(params, "auth", "auth_str", "auth-str").ifBlank { decodeSecret(uri.rawUserInfo) },
            sni = firstQuery(params, "sni", "peer"),
            alpn = parseAlpn(params["alpn"]),
            allowInsecure = firstQuery(params, "allowInsecure", "allow_insecure", "insecure").isEnabledParameter(),
            hysteria2ObfsPassword = firstQuery(params, "obfs", "obfs-password", "obfs_password"),
            hysteria2UpMbps = firstQuery(params, "upmbps", "up_mbps").toIntOrNull() ?: 0,
            hysteria2DownMbps = firstQuery(params, "downmbps", "down_mbps").toIntOrNull() ?: 0,
            raw = raw,
        )
    }

    private fun decodeBase64(value: String): String {
        val compact = value.trim().replace('-', '+').replace('_', '/')
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return Base64.getDecoder().decode(padded).toString(Charsets.UTF_8)
    }

    private fun normalizeTransport(value: String): String = when (val normalized = value.trim().lowercase()) {
        "h2" -> "http"
        "http-upgrade", "http_upgrade" -> "httpupgrade"
        "splithttp", "split-http", "split_http" -> "xhttp"
        else -> normalized
    }

    private fun queryParams(rawQuery: String): Map<String, String> =
        rawQuery.split('&')
            .asSequence()
            .filter(String::isNotBlank)
            .map {
                val index = it.indexOf('=')
                if (index == -1) decode(it) to "" else decode(it.substring(0, index)) to decode(it.substring(index + 1))
            }
            .toMap()

    private fun firstQuery(params: Map<String, String>, vararg names: String): String =
        names.firstNotNullOfOrNull { params[it]?.takeIf(String::isNotBlank) }.orEmpty()

    private fun parseAlpn(value: String?): List<String> = value
        .orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun String?.isEnabledParameter(): Boolean =
        this.equals("1", ignoreCase = true) || this.equals("true", ignoreCase = true)

    private fun decode(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return Uri.decode(value.replace("+", "%2B"))
    }

    private fun decodeSecret(value: String?): String =
        decode(value).replace(' ', '+')

    private fun String.trimProfileTail(): String =
        trim().trimEnd(',', ';', '.', ')', ']', '}', '"', '\'')

    private val PROFILE_LINK_REGEX = Regex(
        pattern = "(?i)(vless|hysteria2|hy2|trojan|vmess|ss|socks5?|wg|wireguard|tuic|hysteria)://[^\\s<>\"']+",
    )

    private val V2RAY_TRANSPORTS = setOf("tcp", "raw", "ws", "grpc", "http", "httpupgrade", "xhttp")
    private val VLESS_TRANSPORTS = V2RAY_TRANSPORTS
    private val TROJAN_TRANSPORTS = V2RAY_TRANSPORTS
    private val VMESS_TRANSPORTS = V2RAY_TRANSPORTS
    private val SHADOWSOCKS_PLUGINS = setOf("obfs-local", "v2ray-plugin")
    private val XHTTP_MODES = setOf("stream-up", "stream-one", "packet-up")
    private const val DEFAULT_XHTTP_MODE = "stream-one"
}
