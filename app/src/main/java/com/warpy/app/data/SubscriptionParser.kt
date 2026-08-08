package com.warpy.app.data

import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.util.Base64

data class ParsedSubscription(
    val profiles: List<VpnProfile>,
    val skipped: Int = 0,
)

object SubscriptionParser {
    private const val MAX_PAYLOAD_BYTES = SubscriptionFetcher.MAX_RESPONSE_BYTES
    private const val MAX_PROFILES = 2_000
    private const val MAX_DECODE_DEPTH = 2

    private val supportedSchemes = Regex(
        "(?i)^(vless|hysteria2|hy2|trojan|vmess|ss|socks5?|wg|wireguard|tuic|hysteria)://",
    )
    private val supportedProtocols = setOf(
        "vless", "hysteria2", "trojan", "vmess", "shadowsocks", "socks", "wireguard", "tuic", "hysteria",
    )
    private val supportedShadowsocksPlugins = setOf("obfs-local", "v2ray-plugin")

    fun parse(payload: String): Result<ParsedSubscription> = runCatching {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Ответ подписки слишком большой"
        }
        parsePayload(payload, 0)
    }

    private fun parsePayload(payload: String, depth: Int): ParsedSubscription {
        val text = payload.removePrefix("\uFEFF").trim()
        require(text.isNotBlank()) { "Подписка пуста" }
        require(!Regex("""(?is)^\s*<(?:!doctype|html|head|body)\b""").containsMatchIn(text)) {
            "Вместо подписки сервер вернул веб-страницу"
        }

        parseStructured(text)?.let { return checked(it) }
        parseLinks(text)?.let { return checked(it) }

        if (depth < MAX_DECODE_DEPTH) {
            decodeBase64(text)?.takeIf { it.trim() != text }?.let { decoded ->
                return parsePayload(decoded, depth + 1)
            }
        }
        error("Не удалось разобрать профили из подписки")
    }

    private fun parseStructured(text: String): ParsedSubscription? {
        val root = when {
            text.startsWith('{') || text.startsWith('[') -> runCatching {
                jsonValue(JSONTokener(text).nextValue())
            }.getOrNull()
            Regex("""(?m)^\s*(proxies|outbounds|endpoints)\s*:""").containsMatchIn(text) -> runCatching {
                val settings = LoadSettings.builder()
                    .setAllowDuplicateKeys(false)
                    .setAllowRecursiveKeys(false)
                    .setMaxAliasesForCollections(0)
                    .setCodePointLimit(MAX_PAYLOAD_BYTES)
                    .build()
                Load(settings).loadFromString(text)
            }.getOrNull()
            else -> null
        } ?: return null

        val rootMap = root.asMap()
        val clash = rootMap?.list("proxies")
        if (clash != null) return parseStructuredProfiles(clash, ::clashProfile)

        val outbounds = if (rootMap != null) {
            rootMap.list("outbounds").orEmpty() + rootMap.list("endpoints").orEmpty()
        } else {
            root.asList() ?: return null
        }
        if (outbounds.isEmpty()) return null
        return parseStructuredProfiles(outbounds, ::singBoxProfile)
    }

    private fun parseStructuredProfiles(
        values: List<*>,
        converter: (Map<*, *>) -> VpnProfile?,
    ): ParsedSubscription {
        val profiles = mutableListOf<VpnProfile>()
        var skipped = 0
        for (value in values) {
            val source = value.asMap()
            if (source == null) {
                skipped += 1
                continue
            }
            val protocol = normalizeProtocol(source.string("type"))
            if (protocol !in supportedProtocols) {
                skipped += 1
                continue
            }
            val profile = runCatching { converter(source) }.getOrNull()
            if (profile == null) {
                skipped += 1
                continue
            }
            if (profiles.none { it.connectionIdentity() == profile.connectionIdentity() }) profiles += profile
            require(profiles.size <= MAX_PROFILES) { "В подписке слишком много профилей" }
        }
        require(profiles.isNotEmpty()) { "Подписка не содержит поддерживаемых профилей" }
        return ParsedSubscription(profiles, skipped)
    }

    private fun parseLinks(text: String): ParsedSubscription? {
        val candidates = text.split(Regex("\\s+"))
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        if (candidates.none { supportedSchemes.containsMatchIn(it) }) return null

        val profiles = mutableListOf<VpnProfile>()
        var skipped = 0
        for (candidate in candidates) {
            if (!supportedSchemes.containsMatchIn(candidate)) {
                skipped += 1
                continue
            }
            val profile = ProfileParser.parse(candidate).getOrNull()
            if (profile == null) {
                skipped += 1
                continue
            }
            if (profiles.none { it.connectionIdentity() == profile.connectionIdentity() }) profiles += profile
            require(profiles.size <= MAX_PROFILES) { "В подписке слишком много профилей" }
        }
        return profiles.takeIf(List<VpnProfile>::isNotEmpty)
            ?.let { ParsedSubscription(it, skipped) }
    }

    private fun singBoxProfile(source: Map<*, *>): VpnProfile? {
        val protocolName = normalizeProtocol(source.string("type"))
        val protocol = protocolName.toProtocol() ?: return null
        val peer = source.list("peers")?.firstOrNull().asMap()
        val server = source.string("server").ifBlank { peer.string("address") }
        val serverPorts = source.stringList("server_ports", "server-ports").joinToString(",")
        val port = source.int("server_port", "server-port")
            ?: peer.int("port")
            ?: firstPort(serverPorts)
            ?: defaultPort(protocol)
        if (server.isBlank() || port !in 1..65535) return null

        val tls = source.map("tls")
        val reality = tls.map("reality")
        val transport = source.map("transport")
        val transportType = normalizeTransport(transport.string("type")) ?: return null
        if (transportType.isNotBlank() && protocol !in setOf(Protocol.Vless, Protocol.Trojan, Protocol.Vmess)) return null
        val hostHeader = transport.map("headers").header("host")
            .ifBlank { transport.stringList("host").firstOrNull().orEmpty() }
        val realityEnabled = reality.boolean("enabled") == true && reality.string("public_key", "public-key").isNotBlank()
        val security = when {
            realityEnabled -> "reality"
            tls.boolean("enabled") == true -> "tls"
            else -> ""
        }
        val base = VpnProfile(
            name = source.string("tag").ifBlank { "$protocolName $server" },
            protocol = protocol,
            server = server,
            port = port,
            security = security,
            sni = tls.string("server_name", "server-name"),
            publicKey = reality.string("public_key", "public-key"),
            shortId = reality.string("short_id", "short-id"),
            fingerprint = tls.map("utls").string("fingerprint").ifBlank { "chrome" },
            alpn = tls.stringList("alpn"),
            allowInsecure = tls.boolean("insecure") == true,
            transport = transportType,
            host = hostHeader,
            path = transport.string("path"),
            serviceName = transport.string("service_name", "service-name"),
            xhttpMode = if (transportType == "xhttp") normalizedXhttpMode(transport.string("mode")) else "",
            multiplex = source.map("multiplex").boolean("enabled") == true,
            raw = "",
        )

        return when (protocol) {
            Protocol.Vless -> base.copy(
                uuid = source.string("uuid").takeIf(String::isNotBlank) ?: return null,
                flow = source.string("flow"),
                packetEncoding = source.string("packet_encoding", "packet-encoding"),
            )
            Protocol.Trojan -> base.copy(
                password = source.secret("password").takeIf(String::isNotBlank) ?: return null,
            )
            Protocol.Hysteria2 -> {
                val obfs = source.map("obfs")
                base.copy(
                    password = source.secret("password").takeIf(String::isNotBlank) ?: return null,
                    hysteria2ObfsType = obfs.string("type"),
                    hysteria2ObfsPassword = obfs.secret("password"),
                    hysteria2ServerPorts = serverPorts,
                    hysteria2HopInterval = source.string("hop_interval", "hop-interval"),
                    hysteria2HopIntervalMax = source.string("hop_interval_max", "hop-interval-max"),
                    hysteria2UpMbps = source.int("up_mbps", "up-mbps") ?: 0,
                    hysteria2DownMbps = source.int("down_mbps", "down-mbps") ?: 0,
                )
            }
            Protocol.Vmess -> base.copy(
                uuid = source.string("uuid").takeIf(String::isNotBlank) ?: return null,
                encryption = source.string("security").ifBlank { "auto" },
                alterId = source.int("alter_id", "alter-id") ?: 0,
                packetEncoding = source.string("packet_encoding", "packet-encoding"),
            )
            Protocol.Shadowsocks -> {
                val plugin = source.string("plugin")
                if (plugin.isNotBlank() && plugin !in supportedShadowsocksPlugins) return null
                base.copy(
                    encryption = source.string("method").takeIf(String::isNotBlank) ?: return null,
                    password = source.secret("password").takeIf(String::isNotBlank) ?: return null,
                    plugin = plugin,
                    pluginOptions = source.string("plugin_opts", "plugin-opts"),
                )
            }
            Protocol.Socks -> base.copy(
                username = source.string("username"),
                password = source.secret("password"),
            )
            Protocol.WireGuard -> base.copy(
                privateKey = source.secret("private_key", "private-key").takeIf(String::isNotBlank) ?: return null,
                peerPublicKey = source.string("peer_public_key", "peer-public-key")
                    .ifBlank { peer.string("public_key", "public-key") }
                    .takeIf(String::isNotBlank) ?: return null,
                preSharedKey = source.secret("pre_shared_key", "pre-shared-key")
                    .ifBlank { peer.secret("pre_shared_key", "pre-shared-key") },
                localAddress = source.stringList("local_address", "local-address", "address").joinToString(",")
                    .takeIf(String::isNotBlank) ?: return null,
                reserved = source.scalarList("reserved").ifEmpty { peer.scalarList("reserved") }.joinToString(","),
                mtu = source.int("mtu") ?: 0,
            )
            Protocol.Tuic -> base.copy(
                uuid = source.string("uuid").takeIf(String::isNotBlank) ?: return null,
                password = source.secret("password"),
                congestionControl = source.string("congestion_control", "congestion-control").ifBlank { "cubic" },
                udpRelayMode = source.string("udp_relay_mode", "udp-relay-mode").ifBlank { "native" },
            )
            Protocol.Hysteria -> base.copy(
                password = source.secret("auth_str", "auth-str", "auth"),
                hysteria2ObfsPassword = source.secret("obfs"),
                hysteria2UpMbps = source.int("up_mbps", "up-mbps") ?: 0,
                hysteria2DownMbps = source.int("down_mbps", "down-mbps") ?: 0,
            )
        }
    }

    private fun clashProfile(source: Map<*, *>): VpnProfile? {
        val protocolName = normalizeProtocol(source.string("type"))
        val protocol = protocolName.toProtocol() ?: return null
        val server = source.string("server")
        val port = source.int("port") ?: defaultPort(protocol)
        if (server.isBlank() || port !in 1..65535) return null

        val reality = source.map("reality-opts", "reality_opts")
        val publicKey = reality.string("public-key", "public_key")
        val transportType = if (protocol in setOf(Protocol.Vless, Protocol.Trojan, Protocol.Vmess)) {
            normalizeTransport(source.string("network")) ?: return null
        } else {
            ""
        }
        val options = when (transportType) {
            "ws" -> source.map("ws-opts", "ws_opts")
            "grpc" -> source.map("grpc-opts", "grpc_opts")
            "http" -> source.map("h2-opts", "h2_opts", "http-opts", "http_opts")
            "httpupgrade" -> source.map("http-upgrade-opts", "http_upgrade_opts")
            "xhttp" -> source.map("xhttp-opts", "xhttp_opts")
            else -> emptyMap<Any?, Any?>()
        }
        val base = VpnProfile(
            name = source.string("name").ifBlank { "$protocolName $server" },
            protocol = protocol,
            server = server,
            port = port,
            security = when {
                publicKey.isNotBlank() -> "reality"
                source.boolean("tls") == true || protocol in setOf(Protocol.Trojan, Protocol.Hysteria2, Protocol.Tuic, Protocol.Hysteria) -> "tls"
                else -> ""
            },
            sni = source.string("servername", "server-name", "sni", "peer"),
            publicKey = publicKey,
            shortId = reality.string("short-id", "short_id"),
            fingerprint = source.string("client-fingerprint", "client_fingerprint", "fingerprint").ifBlank { "chrome" },
            alpn = source.stringList("alpn"),
            allowInsecure = source.boolean("skip-cert-verify", "skip_cert_verify", "insecure") == true,
            transport = transportType,
            host = options.map("headers").header("host").ifBlank { options.stringList("host").firstOrNull().orEmpty() },
            path = options.string("path"),
            serviceName = options.string("grpc-service-name", "grpc_service_name", "service-name", "service_name"),
            xhttpMode = if (transportType == "xhttp") normalizedXhttpMode(options.string("mode")) else "",
            multiplex = source.boolean("mux", "multiplex") == true,
            raw = "",
        )

        return when (protocol) {
            Protocol.Vless -> base.copy(
                uuid = source.string("uuid").takeIf(String::isNotBlank) ?: return null,
                flow = source.string("flow"),
                packetEncoding = source.string("packet-encoding", "packet_encoding"),
            )
            Protocol.Trojan -> base.copy(
                password = source.secret("password").takeIf(String::isNotBlank) ?: return null,
            )
            Protocol.Hysteria2 -> base.copy(
                password = source.secret("password", "auth", "auth-str").takeIf(String::isNotBlank) ?: return null,
                hysteria2ObfsType = source.string("obfs").ifBlank {
                    if (source.secret("obfs-password", "obfs_password").isBlank()) "" else "salamander"
                },
                hysteria2ObfsPassword = source.secret("obfs-password", "obfs_password"),
                hysteria2ServerPorts = source.stringList("ports", "server-ports", "server_ports").joinToString(","),
                hysteria2HopInterval = source.string("hop-interval", "hop_interval"),
                hysteria2HopIntervalMax = source.string("hop-interval-max", "hop_interval_max"),
                hysteria2UpMbps = source.int("up", "up-mbps", "up_mbps") ?: 0,
                hysteria2DownMbps = source.int("down", "down-mbps", "down_mbps") ?: 0,
            )
            Protocol.Vmess -> base.copy(
                uuid = source.string("uuid").takeIf(String::isNotBlank) ?: return null,
                encryption = source.string("cipher").ifBlank { "auto" },
                alterId = source.int("alterId", "alter-id", "alter_id") ?: 0,
                packetEncoding = source.string("packet-encoding", "packet_encoding"),
            )
            Protocol.Shadowsocks -> {
                val plugin = source.string("plugin")
                if (plugin.isNotBlank() && plugin !in supportedShadowsocksPlugins) return null
                base.copy(
                    encryption = source.string("cipher").takeIf(String::isNotBlank) ?: return null,
                    password = source.secret("password").takeIf(String::isNotBlank) ?: return null,
                    plugin = plugin,
                    pluginOptions = source.string("plugin-opts", "plugin_opts"),
                )
            }
            Protocol.Socks -> base.copy(
                username = source.string("username"),
                password = source.secret("password"),
            )
            Protocol.WireGuard -> base.copy(
                privateKey = source.secret("private-key", "private_key").takeIf(String::isNotBlank) ?: return null,
                peerPublicKey = source.string("public-key", "public_key").takeIf(String::isNotBlank) ?: return null,
                preSharedKey = source.secret("pre-shared-key", "pre_shared_key"),
                localAddress = buildList {
                    addAll(source.stringList("ip", "address", "local-address", "local_address"))
                    addAll(source.stringList("ipv6"))
                }.joinToString(",").takeIf(String::isNotBlank) ?: return null,
                reserved = source.scalarList("reserved").joinToString(","),
                mtu = source.int("mtu") ?: 0,
            )
            Protocol.Tuic -> base.copy(
                uuid = source.string("uuid").takeIf(String::isNotBlank) ?: return null,
                password = source.secret("password"),
                congestionControl = source.string("congestion-controller", "congestion_control").ifBlank { "cubic" },
                udpRelayMode = source.string("udp-relay-mode", "udp_relay_mode").ifBlank { "native" },
            )
            Protocol.Hysteria -> base.copy(
                password = source.secret("auth-str", "auth_str", "auth"),
                hysteria2ObfsPassword = source.secret("obfs"),
                hysteria2UpMbps = source.int("up", "up-mbps", "up_mbps") ?: 0,
                hysteria2DownMbps = source.int("down", "down-mbps", "down_mbps") ?: 0,
            )
        }
    }

    private fun normalizeProtocol(value: String): String = when (value.lowercase()) {
        "hy2" -> "hysteria2"
        "ss" -> "shadowsocks"
        "socks5" -> "socks"
        "wg" -> "wireguard"
        else -> value.lowercase()
    }

    private fun normalizeTransport(value: String): String? = when (value.lowercase()) {
        "", "tcp", "raw", "ws", "grpc", "http", "httpupgrade", "xhttp" -> value.lowercase()
        "h2" -> "http"
        "http-upgrade", "http_upgrade" -> "httpupgrade"
        "splithttp", "split-http" -> "xhttp"
        else -> null
    }

    private fun normalizedXhttpMode(value: String): String = when (value.lowercase()) {
        "stream-up", "stream-one", "packet-up" -> value.lowercase()
        "" -> "stream-one"
        else -> error("Неподдерживаемый режим XHTTP: $value")
    }

    private fun String.toProtocol(): Protocol? = when (this) {
        "vless" -> Protocol.Vless
        "hysteria2" -> Protocol.Hysteria2
        "trojan" -> Protocol.Trojan
        "vmess" -> Protocol.Vmess
        "shadowsocks" -> Protocol.Shadowsocks
        "socks" -> Protocol.Socks
        "wireguard" -> Protocol.WireGuard
        "tuic" -> Protocol.Tuic
        "hysteria" -> Protocol.Hysteria
        else -> null
    }

    private fun defaultPort(protocol: Protocol): Int = when (protocol) {
        Protocol.Socks -> 1080
        Protocol.WireGuard -> 51820
        else -> 443
    }

    private fun firstPort(value: String): Int? = Regex("\\d{1,5}").find(value)?.value?.toIntOrNull()

    private fun checked(value: ParsedSubscription): ParsedSubscription {
        require(value.profiles.isNotEmpty()) { "Подписка не содержит поддерживаемых профилей" }
        require(value.profiles.size <= MAX_PROFILES) { "В подписке слишком много профилей" }
        return value
    }

    private fun decodeBase64(value: String): String? {
        val compact = value.replace(Regex("\\s+"), "").replace('-', '+').replace('_', '/')
        if (compact.isBlank() || compact.length % 4 == 1 || !Regex("^[A-Za-z0-9+/]*={0,2}$").matches(compact)) return null
        return runCatching {
            val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
            Base64.getDecoder().decode(padded).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    private fun Any?.asMap(): Map<*, *>? = this as? Map<*, *>
    private fun Any?.asList(): List<*>? = this as? List<*>
    private fun Map<*, *>?.value(vararg keys: String): Any? = this?.entries
        ?.firstOrNull { (key, _) -> keys.any { it.equals(key?.toString(), ignoreCase = true) } }
        ?.value
    private fun Map<*, *>?.string(vararg keys: String): String = value(*keys)?.toString()?.trim().orEmpty()
    private fun Map<*, *>?.secret(vararg keys: String): String = when (val value = value(*keys)) {
        is String -> value
        is Number -> value.toString()
        else -> ""
    }
    private fun Map<*, *>?.int(vararg keys: String): Int? = when (val value = value(*keys)) {
        is Number -> value.toInt()
        else -> value?.toString()?.toIntOrNull()
    }
    private fun Map<*, *>?.boolean(vararg keys: String): Boolean? = when (val value = value(*keys)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value?.toString()?.let { it == "1" || it.equals("true", ignoreCase = true) }
    }
    private fun Map<*, *>?.map(vararg keys: String): Map<*, *> = value(*keys).asMap().orEmpty()
    private fun Map<*, *>?.list(vararg keys: String): List<*>? = value(*keys).asList()
    private fun Map<*, *>?.stringList(vararg keys: String): List<String> = when (val value = value(*keys)) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
        null -> emptyList()
        else -> listOf(value.toString().trim()).filter(String::isNotBlank)
    }
    private fun Map<*, *>?.scalarList(vararg keys: String): List<String> = stringList(*keys)
    private fun Map<*, *>?.header(name: String): String = this?.entries
        ?.firstOrNull { (key, _) -> name.equals(key?.toString(), ignoreCase = true) }
        ?.value
        .let { value ->
            when (value) {
                is List<*> -> value.firstOrNull()?.toString()?.trim().orEmpty()
                else -> value?.toString()?.trim().orEmpty()
            }
        }
}
