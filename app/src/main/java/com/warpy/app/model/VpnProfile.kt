package com.warpy.app.model

enum class Protocol(val isUdpBased: Boolean = false) {
    Vless,
    Hysteria2(isUdpBased = true),
    Trojan,
    Vmess,
    Shadowsocks,
    Socks,
    WireGuard(isUdpBased = true),
    Tuic(isUdpBased = true),
    Hysteria(isUdpBased = true),
    Naive,
}

enum class AppTunnelMode {
    All,
    Include,
    Exclude,
}

enum class AppLanguage {
    Russian,
    English,
}

data class VpnProfile(
    val name: String,
    val protocol: Protocol,
    val server: String,
    val port: Int,
    val uuid: String = "",
    val password: String = "",
    val sni: String = "",
    val security: String = "",
    val flow: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val fingerprint: String = "chrome",
    val alpn: List<String> = emptyList(),
    val allowInsecure: Boolean = false,
    val hysteria2ObfsType: String = "",
    val hysteria2ObfsPassword: String = "",
    val hysteria2ServerPorts: String = "",
    val hysteria2HopInterval: String = "",
    val hysteria2HopIntervalMax: String = "",
    val hysteria2UpMbps: Int = 0,
    val hysteria2DownMbps: Int = 0,
    val transport: String = "",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val xhttpMode: String = "",
    val multiplex: Boolean = false,
    val group: String = "",
    val username: String = "",
    val encryption: String = "",
    val plugin: String = "",
    val pluginOptions: String = "",
    val alterId: Int = 0,
    val packetEncoding: String = "",
    val privateKey: String = "",
    val peerPublicKey: String = "",
    val preSharedKey: String = "",
    val localAddress: String = "",
    val reserved: String = "",
    val mtu: Int = 0,
    val congestionControl: String = "",
    val udpRelayMode: String = "",
    val naiveQuic: Boolean = false,
    val raw: String = "",
)

data class AppSettings(
    val profiles: List<VpnProfile> = emptyList(),
    val activeProfileIndex: Int = 0,
    val adBlockEnabled: Boolean = true,
    val blockQuic: Boolean = false,
    val bypassLan: Boolean = true,
    val stabilityModeEnabled: Boolean = true,
    val autoStartOnBoot: Boolean = true,
    val language: AppLanguage = AppLanguage.English,
    val mtu: Int = 1400,
    val appTunnelMode: AppTunnelMode = AppTunnelMode.Exclude,
    val tunneledApps: Set<String> = emptySet(),
    val siteTunnelMode: AppTunnelMode = AppTunnelMode.Exclude,
    val tunneledSites: Set<String> = emptySet(),
) {
    val profile: VpnProfile?
        get() = profiles.getOrNull(activeProfileIndex)
}

enum class VpnStatus {
    Idle,
    Connecting,
    Connected,
    Error,
}

data class Diagnostics(
    val status: VpnStatus = VpnStatus.Idle,
    val message: String = "VPN выключен",
    val generatedConfig: String = "",
    val pingText: String = "—",
    val speedText: String = "—",
    val uptimeText: String = "",
    val connectedAtMillis: Long = 0L,
    val runtimeProfileIndex: Int? = null,
    val speedTest: SpeedTestState = SpeedTestState(),
)

data class SpeedTestState(
    val running: Boolean = false,
    val stage: String = "",
    val pingText: String = "—",
    val downloadText: String = "—",
    val uploadText: String = "—",
    val liveMbps: Float = 0f,
    val errorText: String = "",
)
