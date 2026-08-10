package com.warpy.app.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.warpy.app.model.AppSettings
import com.warpy.app.model.AppLanguage
import com.warpy.app.model.AppTunnelMode
import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val PROFILES_SCHEMA_VERSION_KEY = "profilesSchemaVersion"
private const val PROFILES_SCHEMA_VERSION = 5
private const val PROFILES_JSON_KEY = "profilesJson"
private const val PROFILES_JSON_BACKUP_KEY = "profilesJsonBackup"

internal enum class ProfilesSnapshotSource {
    Primary,
    Backup,
    Missing,
    Invalid,
}

internal data class ProfilesSnapshot(
    val profiles: List<VpnProfile>?,
    val source: ProfilesSnapshotSource,
    val rawJson: String? = null,
)

internal fun selectProfilesSnapshot(
    primaryJson: String?,
    backupJson: String?,
): ProfilesSnapshot {
    if (primaryJson != null) {
        parseProfilesJson(primaryJson).getOrNull()?.let { profiles ->
            return ProfilesSnapshot(
                profiles = profiles,
                source = ProfilesSnapshotSource.Primary,
                rawJson = primaryJson,
            )
        }
    }
    if (backupJson != null) {
        parseProfilesJson(backupJson).getOrNull()?.let { profiles ->
            return ProfilesSnapshot(
                profiles = profiles,
                source = ProfilesSnapshotSource.Backup,
                rawJson = backupJson,
            )
        }
    }
    return ProfilesSnapshot(
        profiles = null,
        source = if (primaryJson == null && backupJson == null) {
            ProfilesSnapshotSource.Missing
        } else {
            ProfilesSnapshotSource.Invalid
        },
    )
}

internal fun migrateProfilesForSchema(
    profiles: List<VpnProfile>,
    storedSchemaVersion: Int,
): List<VpnProfile> {
    if (storedSchemaVersion >= PROFILES_SCHEMA_VERSION) return profiles
    if (storedSchemaVersion <= 3) return profiles

    val vlessGroups = profiles
        .filter { it.protocol == Protocol.Vless && it.uuid.isNotBlank() }
        .groupBy { it.uuid }
    val passwordGroups = profiles
        .filter {
            (it.protocol == Protocol.Hysteria2 || it.protocol == Protocol.Trojan) &&
                it.password.isNotBlank()
        }
        .groupBy { it.protocol to it.password }

    return profiles.map { profile ->
        if (profile.group.isNotBlank()) return@map profile
        val belongedToLegacySubscription = when (profile.protocol) {
            Protocol.Vless -> (vlessGroups[profile.uuid]?.size ?: 0) >= 3
            Protocol.Hysteria2, Protocol.Trojan ->
                (passwordGroups[profile.protocol to profile.password]?.size ?: 0) >= 3
            else -> false
        }
        if (belongedToLegacySubscription) profile.copy(group = "BlancVPN") else profile
    }
}

@SuppressLint("ApplySharedPref")
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("warpy", Context.MODE_PRIVATE)
    private val secureStore = EncryptedSharedPreferences.create(
            context,
            "warpy_secure",
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun load(): AppSettings {
        var primaryJson = secureStore.getString(PROFILES_JSON_KEY, null)
        if (primaryJson == null) {
            val plaintextJson = prefs.getString(PROFILES_JSON_KEY, null)
            if (plaintextJson != null && parseProfilesJson(plaintextJson).isSuccess) {
                if (writeProfilesJson(plaintextJson, previousPrimary = null)) {
                    prefs.edit().remove(PROFILES_JSON_KEY).commit()
                }
                primaryJson = plaintextJson
            }
        }
        val storedSnapshot = selectProfilesSnapshot(
            primaryJson = primaryJson,
            backupJson = secureStore.getString(PROFILES_JSON_BACKUP_KEY, null),
        )
        if (storedSnapshot.source == ProfilesSnapshotSource.Backup) {
            storedSnapshot.rawJson?.let { recoveredJson ->
                secureStore.edit()
                    .putString(PROFILES_JSON_KEY, recoveredJson)
                    .commit()
            }
        }

        val parsed = storedSnapshot.profiles.orEmpty()
        val storedSchemaVersion = prefs.getInt(PROFILES_SCHEMA_VERSION_KEY, 0)
        val hasStoredSnapshot = storedSnapshot.profiles != null
        val migratedParsed = if (hasStoredSnapshot) {
            migrateProfilesForSchema(parsed, storedSchemaVersion)
        } else {
            parsed
        }

        val profiles = if (hasStoredSnapshot) {
            migratedParsed
        } else {
            val legacy = loadLegacyProfile()
            if (legacy != null) {
                val list = listOf(legacy)
                if (writeProfiles(list)) {
                    removeLegacyProfile()
                }
                list
            } else {
                emptyList()
            }
        }

        if (hasStoredSnapshot && storedSchemaVersion < PROFILES_SCHEMA_VERSION) {
            if (writeProfiles(migratedParsed)) {
                prefs.edit()
                    .putInt(PROFILES_SCHEMA_VERSION_KEY, PROFILES_SCHEMA_VERSION)
                    .commit()
            }
        }

        val activeIndex = prefs.getInt("activeProfileIndex", 0).coerceIn(0, profiles.lastIndex.coerceAtLeast(0))
        val blockQuicConfigured = prefs.getBoolean("blockQuicUserConfigured", false)

        return AppSettings(
            profiles = profiles,
            activeProfileIndex = activeIndex,
            adBlockEnabled = prefs.getBoolean("adBlockEnabled", true),
            blockQuic = if (blockQuicConfigured) prefs.getBoolean("blockQuic", false) else false,
            bypassLan = prefs.getBoolean("bypassLan", true),
            stabilityModeEnabled = prefs.getBoolean("stabilityModeEnabled", true),
            autoStartOnBoot = prefs.getBoolean("autoStartOnBoot", true),
            language = loadLanguage(),
            mtu = prefs.getInt("mtu", 1400).let { if (it == 0) 0 else it.coerceIn(1280, 1500) },
            appTunnelMode = prefs.getString("appTunnelMode", AppTunnelMode.Exclude.name)
                ?.let { stored -> AppTunnelMode.entries.firstOrNull { it.name == stored } }
                ?: AppTunnelMode.Exclude,
            tunneledApps = prefs.getStringSet("tunneledApps", emptySet()).orEmpty(),
            siteTunnelMode = prefs.getString("siteTunnelMode", AppTunnelMode.Exclude.name)
                ?.let { stored -> AppTunnelMode.entries.firstOrNull { it.name == stored } }
                ?: AppTunnelMode.Exclude,
            tunneledSites = prefs.getStringSet("tunneledSites", emptySet()).orEmpty(),
        )
    }

    fun save(settings: AppSettings): Boolean {
        if (!writeProfiles(settings.profiles)) return false

        return prefs.edit().apply {
            putInt(PROFILES_SCHEMA_VERSION_KEY, PROFILES_SCHEMA_VERSION)
            putInt("activeProfileIndex", settings.activeProfileIndex.coerceIn(0, settings.profiles.lastIndex.coerceAtLeast(0)))
            putBoolean("adBlockEnabled", settings.adBlockEnabled)
            remove("youtubeFilterEnabled")
            putBoolean("blockQuic", settings.blockQuic)
            putBoolean("bypassLan", settings.bypassLan)
            putBoolean("stabilityModeEnabled", settings.stabilityModeEnabled)
            putBoolean("autoStartOnBoot", settings.autoStartOnBoot)
            putString("language", settings.language.name)
            putInt("mtu", if (settings.mtu == 0) 0 else settings.mtu.coerceIn(1280, 1500))
            putString("appTunnelMode", settings.appTunnelMode.name)
            putStringSet("tunneledApps", settings.tunneledApps)
            putString("siteTunnelMode", settings.siteTunnelMode.name)
            putStringSet("tunneledSites", settings.tunneledSites)
        }.commit()
    }

    private fun loadLanguage(): AppLanguage {
        val stored = prefs.getString("language", null)
        val language = when (stored) {
            AppLanguage.Russian.name -> AppLanguage.Russian
            AppLanguage.English.name -> AppLanguage.English
            else -> if (Locale.getDefault().language.equals("ru", ignoreCase = true)) {
                AppLanguage.Russian
            } else {
                AppLanguage.English
            }
        }
        if (stored != language.name) {
            prefs.edit().putString("language", language.name).commit()
        }
        return language
    }

    fun markBlockQuicUserConfigured(): Boolean =
        prefs.edit().putBoolean("blockQuicUserConfigured", true).commit()

    private fun loadLegacyProfile(): VpnProfile? {
        val protocol = prefs.getString("protocol", null)?.let { stored ->
            Protocol.entries.firstOrNull { it.name == stored }
        } ?: return null

        return VpnProfile(
            name = prefs.getString("name", "") ?: "",
            protocol = protocol,
            server = prefs.getString("server", "") ?: "",
            port = prefs.getInt("port", 443),
            uuid = prefs.getString("uuid", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            sni = prefs.getString("sni", "") ?: "",
            security = prefs.getString("security", "") ?: "",
            flow = prefs.getString("flow", "") ?: "",
            publicKey = prefs.getString("publicKey", "") ?: "",
            shortId = prefs.getString("shortId", "") ?: "",
            fingerprint = prefs.getString("fingerprint", "chrome") ?: "chrome",
            allowInsecure = prefs.getBoolean("allowInsecure", false),
            hysteria2ObfsType = prefs.getString("hysteria2ObfsType", "") ?: "",
            hysteria2ObfsPassword = prefs.getString("hysteria2ObfsPassword", "") ?: "",
            hysteria2ServerPorts = prefs.getString("hysteria2ServerPorts", "") ?: "",
            hysteria2HopInterval = prefs.getString("hysteria2HopInterval", "") ?: "",
            hysteria2HopIntervalMax = prefs.getString("hysteria2HopIntervalMax", "") ?: "",
            hysteria2UpMbps = prefs.getInt("hysteria2UpMbps", 0),
            hysteria2DownMbps = prefs.getInt("hysteria2DownMbps", 0),
        )
    }

    private fun writeProfiles(profiles: List<VpnProfile>): Boolean =
        writeProfilesJson(serializeProfilesJson(profiles))

    private fun writeProfilesJson(
        json: String,
        previousPrimary: String? = secureStore.getString(PROFILES_JSON_KEY, null),
    ): Boolean {
        if (parseProfilesJson(json).isFailure) return false
        val editor = secureStore.edit()
        if (previousPrimary != null &&
            previousPrimary != json &&
            parseProfilesJson(previousPrimary).isSuccess
        ) {
            editor.putString(PROFILES_JSON_BACKUP_KEY, previousPrimary)
        }
        return editor
            .putString(PROFILES_JSON_KEY, json)
            .commit()
    }

    private fun removeLegacyProfile() {
        prefs.edit().apply {
            remove("protocol")
            remove("name")
            remove("server")
            remove("port")
            remove("uuid")
            remove("password")
            remove("sni")
            remove("security")
            remove("flow")
            remove("publicKey")
            remove("shortId")
            remove("fingerprint")
            remove("allowInsecure")
            remove("hysteria2ObfsType")
            remove("hysteria2ObfsPassword")
            remove("hysteria2ServerPorts")
            remove("hysteria2HopInterval")
            remove("hysteria2HopIntervalMax")
            remove("hysteria2UpMbps")
            remove("hysteria2DownMbps")
        }.commit()
    }
}

internal fun parseProfilesJson(value: String): Result<List<VpnProfile>> = runCatching {
    val array = JSONArray(value)
    List(array.length()) { index -> array.getJSONObject(index).toProfile() }
}

internal fun serializeProfilesJson(profiles: List<VpnProfile>): String {
    val array = JSONArray()
    profiles.forEach { array.put(it.toJson()) }
    return array.toString()
}

private fun JSONObject.toProfile(): VpnProfile = VpnProfile(
        name = optString("name"),
        protocol = Protocol.entries.firstOrNull { it.name == optString("protocol") } ?: Protocol.Hysteria2,
        server = optString("server"),
        port = optInt("port", 443),
        uuid = optString("uuid"),
        password = optString("password"),
        sni = optString("sni"),
        security = optString("security"),
        flow = optString("flow"),
        publicKey = optString("publicKey"),
        shortId = optString("shortId"),
        fingerprint = optString("fingerprint", "chrome"),
        alpn = optStringList("alpn"),
        allowInsecure = optBoolean("allowInsecure"),
        hysteria2ObfsType = optString("hysteria2ObfsType"),
        hysteria2ObfsPassword = optString("hysteria2ObfsPassword"),
        hysteria2ServerPorts = optString("hysteria2ServerPorts"),
        hysteria2HopInterval = optString("hysteria2HopInterval"),
        hysteria2HopIntervalMax = optString("hysteria2HopIntervalMax"),
        hysteria2UpMbps = optInt("hysteria2UpMbps"),
        hysteria2DownMbps = optInt("hysteria2DownMbps"),
        transport = optString("transport"),
        host = optString("host"),
        path = optString("path"),
        serviceName = optString("serviceName"),
        xhttpMode = optString("xhttpMode"),
        multiplex = optBoolean("multiplex"),
        group = optString("group"),
        username = optString("username"),
        encryption = optString("encryption"),
        plugin = optString("plugin"),
        pluginOptions = optString("pluginOptions"),
        alterId = optInt("alterId"),
        packetEncoding = optString("packetEncoding"),
        privateKey = optString("privateKey"),
        peerPublicKey = optString("peerPublicKey"),
        preSharedKey = optString("preSharedKey"),
        localAddress = optString("localAddress"),
        reserved = optString("reserved"),
        mtu = optInt("mtu"),
        congestionControl = optString("congestionControl"),
        udpRelayMode = optString("udpRelayMode"),
        raw = optString("raw"),
    )

private fun VpnProfile.toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("protocol", protocol.name)
        .put("server", server)
        .put("port", port)
        .put("uuid", uuid)
        .put("password", password)
        .put("sni", sni)
        .put("security", security)
        .put("flow", flow)
        .put("publicKey", publicKey)
        .put("shortId", shortId)
        .put("fingerprint", fingerprint)
        .put("alpn", JSONArray(alpn))
        .put("allowInsecure", allowInsecure)
        .put("hysteria2ObfsType", hysteria2ObfsType)
        .put("hysteria2ObfsPassword", hysteria2ObfsPassword)
        .put("hysteria2ServerPorts", hysteria2ServerPorts)
        .put("hysteria2HopInterval", hysteria2HopInterval)
        .put("hysteria2HopIntervalMax", hysteria2HopIntervalMax)
        .put("hysteria2UpMbps", hysteria2UpMbps)
        .put("hysteria2DownMbps", hysteria2DownMbps)
        .put("transport", transport)
        .put("host", host)
        .put("path", path)
        .put("serviceName", serviceName)
        .put("xhttpMode", xhttpMode)
        .put("multiplex", multiplex)
        .put("group", group)
        .put("username", username)
        .put("encryption", encryption)
        .put("plugin", plugin)
        .put("pluginOptions", pluginOptions)
        .put("alterId", alterId)
        .put("packetEncoding", packetEncoding)
        .put("privateKey", privateKey)
        .put("peerPublicKey", peerPublicKey)
        .put("preSharedKey", preSharedKey)
        .put("localAddress", localAddress)
        .put("reserved", reserved)
        .put("mtu", mtu)
        .put("congestionControl", congestionControl)
        .put("udpRelayMode", udpRelayMode)
        .put("raw", raw)

private fun JSONObject.optStringList(name: String): List<String> = when (val value = opt(name)) {
    is JSONArray -> List(value.length()) { index -> value.optString(index) }
    is String -> value.split(',')
    else -> emptyList()
}.map(String::trim).filter(String::isNotBlank)
