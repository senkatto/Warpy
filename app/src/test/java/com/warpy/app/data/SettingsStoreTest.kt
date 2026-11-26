package com.warpy.app.data

import android.content.SharedPreferences
import com.warpy.app.model.AppSettings
import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SettingsStoreTest {
    private val prefs = MemoryPreferences()
    private val securePrefs = MemoryPreferences()
    private val uiStore = SettingsStore(prefs, securePrefs)
    private val serviceStore = SettingsStore(prefs, securePrefs)
    private val profiles = listOf("old", "backup", "new").map { name ->
        VpnProfile(name, Protocol.Vless, "$name.example.com", 443)
    }

    @Test
    fun delayedServiceSelectionDoesNotRestoreDeletedProfiles() {
        assertTrue(uiStore.save(AppSettings(profiles = profiles, activeProfileIndex = 2)))
        val switchSnapshot = serviceStore.load()
        val remaining = profiles.drop(2)
        assertTrue(uiStore.save(switchSnapshot.copy(profiles = remaining, activeProfileIndex = 0)))

        assertFalse(serviceStore.saveSelectedProfile(0, switchSnapshot))

        val restored = SettingsStore(prefs, securePrefs).load()
        assertEquals(remaining, restored.profiles)
        assertEquals("new", restored.profile?.name)
    }

    @Test
    fun delayedSwitchCannotReplaceANewerUserSelection() {
        assertTrue(uiStore.save(AppSettings(profiles = profiles, activeProfileIndex = 1)))
        val switchSnapshot = serviceStore.load()
        assertTrue(uiStore.save(switchSnapshot.copy(activeProfileIndex = 2)))

        assertFalse(serviceStore.saveSelectedProfile(0, switchSnapshot))
        assertEquals("new", uiStore.load().profile?.name)
    }

    @Test
    fun selectedProfileUpdatePreservesNewerRoutingSettingsAndProfileSnapshots() {
        assertTrue(uiStore.save(AppSettings(profiles = profiles)))
        val switchSnapshot = serviceStore.load()
        val updated = switchSnapshot.copy(adBlockEnabled = false, tunneledSites = setOf("ru"))
        assertTrue(uiStore.save(updated))
        val encryptedBefore = securePrefs.all

        assertTrue(serviceStore.saveSelectedProfile(2, switchSnapshot))

        assertEquals(updated.copy(activeProfileIndex = 2), uiStore.load())
        assertEquals(encryptedBefore, securePrefs.all)
    }

    @Test
    fun deletingEveryProfileSurvivesALateSwitchAndReload() {
        assertTrue(uiStore.save(AppSettings(profiles = profiles)))
        val switchSnapshot = serviceStore.load()
        assertTrue(uiStore.save(switchSnapshot.copy(profiles = emptyList())))

        assertFalse(serviceStore.saveSelectedProfile(0, switchSnapshot))
        assertEquals(emptyList(), SettingsStore(prefs, securePrefs).load().profiles)
    }

    @Test
    fun profileEditsInvalidateAnInFlightSwitchEvenWhenIndicesMatch() {
        assertTrue(uiStore.save(AppSettings(profiles = profiles)))
        val switchSnapshot = serviceStore.load()
        val edited = profiles.map { it.copy(server = "replacement.example.com") }
        assertTrue(uiStore.save(switchSnapshot.copy(profiles = edited)))

        assertFalse(serviceStore.saveSelectedProfile(1, switchSnapshot))
        assertEquals(edited, uiStore.load().profiles)
    }

    @Test
    fun outOfRangeSelectionDoesNotChangeTheStoredProfile() {
        assertTrue(uiStore.save(AppSettings(profiles = profiles, activeProfileIndex = 2)))
        val snapshot = serviceStore.load()

        assertFalse(serviceStore.saveSelectedProfile(-1, snapshot))
        assertFalse(serviceStore.saveSelectedProfile(profiles.size, snapshot))
        assertEquals(snapshot, uiStore.load())
    }

    @Test
    fun failedSelectionCommitIsReported() {
        assertTrue(uiStore.save(AppSettings(profiles = profiles)))
        val snapshot = serviceStore.load()
        prefs.commitSucceeds = false

        assertFalse(serviceStore.saveSelectedProfile(2, snapshot))
    }
}

private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String?, Any?>()
    var commitSucceeds = true

    override fun getAll(): MutableMap<String, *> = values.filterKeys { it != null }
        .mapKeys { it.key!! }.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String?, Any?>()
        private var clear = false
        override fun putString(key: String?, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String?, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String?, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String?, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String?) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (!commitSucceeds) return false
            if (clear) values.clear()
            changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }
        override fun apply() { commit() }
    }
}
