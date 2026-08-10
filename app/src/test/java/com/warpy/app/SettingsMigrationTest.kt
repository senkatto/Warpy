package com.warpy.app

import com.warpy.app.data.migrateProfilesForSchema
import com.warpy.app.data.parseProfilesJson
import com.warpy.app.data.ProfilesSnapshotSource
import com.warpy.app.data.selectProfilesSnapshot
import com.warpy.app.data.serializeProfilesJson
import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsMigrationTest {
    @Test
    fun schema2KeepsCertificateVerificationEnabledByDefault() {
        val profiles = listOf(
            VpnProfile("hy2", Protocol.Hysteria2, "example.com", 443),
            VpnProfile("vless", Protocol.Vless, "example.com", 443),
        )

        val migrated = migrateProfilesForSchema(profiles, storedSchemaVersion = 2)

        assertFalse(migrated[0].allowInsecure)
        assertFalse(migrated[1].allowInsecure)
    }

    @Test
    fun currentSchemaPreservesExplicitCertificateVerification() {
        val profile = VpnProfile("hy2", Protocol.Hysteria2, "example.com", 443)

        val migrated = migrateProfilesForSchema(listOf(profile), storedSchemaVersion = 5)

        assertFalse(migrated.single().allowInsecure)
    }

    @Test
    fun schema3PreservesLegacyProviderGrouping() {
        val profiles = listOf(
            VpnProfile("Imported", Protocol.Vless, "vpn.example.com", 443, group = "BlancVPN"),
            VpnProfile("Personal", Protocol.Hysteria2, "hy2.example.com", 443, group = "Personal"),
        )

        val migrated = migrateProfilesForSchema(profiles, storedSchemaVersion = 3)

        assertEquals("BlancVPN", migrated.first().group)
        assertEquals("Personal", migrated.last().group)
    }

    @Test
    fun currentSchemaPreservesExplicitGroups() {
        val profile = VpnProfile(
            "Imported",
            Protocol.Vless,
            "vpn.example.com",
            443,
            group = "BlancVPN",
        )

        val migrated = migrateProfilesForSchema(listOf(profile), storedSchemaVersion = 5)

        assertEquals("BlancVPN", migrated.single().group)
    }

    @Test
    fun schema4RecoversGroupsRemovedByThePreviousMigration() {
        val sharedUuid = "00000000-0000-4000-8000-000000000001"
        val profiles = listOf(
            VpnProfile("One", Protocol.Vless, "one.example.com", 443, uuid = sharedUuid),
            VpnProfile("Two", Protocol.Vless, "two.example.com", 443, uuid = sharedUuid),
            VpnProfile("Three", Protocol.Vless, "three.example.com", 443, uuid = sharedUuid),
            VpnProfile("Personal", Protocol.Vless, "personal.example.com", 443, uuid = "other"),
        )

        val migrated = migrateProfilesForSchema(profiles, storedSchemaVersion = 4)

        assertTrue(migrated.take(3).all { it.group == "BlancVPN" })
        assertTrue(migrated.last().group.isBlank())
    }

    @Test
    fun validPrimarySnapshotWinsOverBackupEvenWhenItIsEmpty() {
        val backup = serializeProfilesJson(
            listOf(VpnProfile("old", Protocol.Vless, "old.example.com", 443)),
        )

        val selected = selectProfilesSnapshot(primaryJson = "[]", backupJson = backup)

        assertEquals(ProfilesSnapshotSource.Primary, selected.source)
        assertEquals(emptyList(), selected.profiles)
    }

    @Test
    fun validBackupRecoversACorruptPrimarySnapshot() {
        val backupProfiles = listOf(
            VpnProfile("recovered", Protocol.Hysteria2, "vpn.example.com", 443),
        )
        val backup = serializeProfilesJson(backupProfiles)

        val selected = selectProfilesSnapshot(primaryJson = "{broken", backupJson = backup)

        assertEquals(ProfilesSnapshotSource.Backup, selected.source)
        assertEquals(backupProfiles, selected.profiles)
        assertEquals(backup, selected.rawJson)
    }

    @Test
    fun invalidSnapshotsAreNotMistakenForAnEmptyProfileList() {
        val selected = selectProfilesSnapshot(
            primaryJson = "{broken",
            backupJson = "[not-json]",
        )

        assertEquals(ProfilesSnapshotSource.Invalid, selected.source)
        assertNull(selected.profiles)
    }

    @Test
    fun profileJsonRoundTripPreservesConnectionFields() {
        val original = listOf(
            VpnProfile(
                name = "xhttp",
                protocol = Protocol.Vless,
                server = "vpn.example.com",
                port = 443,
                uuid = "00000000-0000-4000-8000-000000000001",
                sni = "cdn.example.com",
                security = "reality",
                publicKey = "public-key",
                shortId = "abcd",
                alpn = listOf("h2", "http/1.1"),
                transport = "xhttp",
                host = "cdn.example.com",
                path = "/warpy",
                xhttpMode = "auto",
                packetEncoding = "packetaddr",
                plugin = "v2ray-plugin",
                pluginOptions = "tls;host=cdn.example.com;path=/ws",
                multiplex = true,
                group = "Personal",
            ),
        )

        val restored = parseProfilesJson(serializeProfilesJson(original)).getOrThrow()

        assertEquals(original, restored)
    }
}
