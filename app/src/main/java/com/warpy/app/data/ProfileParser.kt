package com.warpy.app.data

import android.net.Uri
import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import java.net.URI

object ProfileParser {
    fun parse(raw: String): Result<VpnProfile> = runCatching {
        val value = extractProfileLink(raw)
        require(value.isNotBlank()) { "Paste a VLESS or Hysteria2 link" }

        when {
            value.startsWith("vless://", ignoreCase = true) -> parseVless(value)
            value.startsWith("hysteria2://", ignoreCase = true) || value.startsWith("hy2://", ignoreCase = true) ->
                parseHysteria2(value)
            value.startsWith("trojan://", ignoreCase = true) -> parseTrojan(value)
            else -> error("Only vless://, hysteria2:// and trojan:// links are supported")
        }
    }

    private fun extractProfileLink(raw: String): String {
        val value = raw.trim()
        if (
            value.startsWith("vless://", ignoreCase = true) ||
            value.startsWith("hysteria2://", ignoreCase = true) ||
            value.startsWith("hy2://", ignoreCase = true) ||
            value.startsWith("trojan://", ignoreCase = true)
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

        val transport = (
            uri.getQueryParameter("type")
                ?: uri.getQueryParameter("transport").orEmpty()
            ).lowercase()
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
            transport = transport,
            host = uri.getQueryParameter("host").orEmpty(),
            path = uri.getQueryParameter("path").orEmpty(),
            serviceName = uri.getQueryParameter("serviceName").orEmpty(),
            xhttpMode = if (transport == "xhttp") xhttpMode else "",
            multiplex = uri.getQueryParameter("mux") == "1" || uri.getQueryParameter("multiplex") == "1",
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
            allowInsecure = firstQuery(params, "allowInsecure", "allow_insecure", "insecure")
                .isEnabledParameter(),
            hysteria2ObfsType = firstQuery(params, "obfs", "obfs-type").ifBlank { if (params.containsKey("obfs-password") || params.containsKey("obfs_password")) "salamander" else "" },
            hysteria2ObfsPassword = firstQuery(params, "obfs-password", "obfs_password").replace(' ', '+'),
            hysteria2ServerPorts = firstQuery(params, "server_ports", "server-ports", "mport", "ports"),
            hysteria2HopInterval = firstQuery(params, "hop_interval", "hop-interval"),
            hysteria2HopIntervalMax = firstQuery(params, "hop_interval_max", "hop-interval-max"),
            hysteria2UpMbps = firstQuery(params, "up_mbps", "upmbps").toIntOrNull() ?: 0,
            hysteria2DownMbps = firstQuery(params, "down_mbps", "downmbps").toIntOrNull() ?: 0,
        )
    }

    private fun parseTrojan(raw: String): VpnProfile {
        val uri = Uri.parse(raw)
        val server = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: 443
        val name = decode(uri.fragment).ifBlank { server }

        require(server.isNotBlank()) { "Trojan server is missing" }
        require(uri.userInfo.orEmpty().isNotBlank()) { "Trojan password is missing" }

        val transport = (
            uri.getQueryParameter("type")
                ?: uri.getQueryParameter("transport").orEmpty()
            ).lowercase()
        if (transport.isNotBlank() && transport !in TROJAN_TRANSPORTS) {
            throw IllegalArgumentException("Профиль содержит пока неподдерживаемый транспорт: $transport")
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
            allowInsecure = uri.getQueryParameter("allowInsecure").isEnabledParameter() ||
                uri.getQueryParameter("insecure").isEnabledParameter(),
            transport = transport,
            host = uri.getQueryParameter("host").orEmpty(),
            path = uri.getQueryParameter("path").orEmpty(),
            serviceName = uri.getQueryParameter("serviceName").orEmpty(),
            multiplex = uri.getQueryParameter("mux") == "1" || uri.getQueryParameter("multiplex") == "1",
        )
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
        pattern = "(?i)(vless|hysteria2|hy2|trojan)://[^\\s<>\"']+",
    )

    private val VLESS_TRANSPORTS = setOf("tcp", "ws", "grpc", "xhttp")
    private val TROJAN_TRANSPORTS = setOf("tcp", "ws", "grpc")
    private val XHTTP_MODES = setOf("stream-up", "stream-one", "packet-up")
    private const val DEFAULT_XHTTP_MODE = "stream-one"
}
