package com.warpy.app

import com.warpy.app.model.AppSettings
import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ProfileDeletionTest {
    private val profiles = listOf("one", "two", "three").map { name ->
        VpnProfile(name = name, protocol = Protocol.Vless, server = "127.0.0.1", port = 443)
    }

    @Test
    fun removingProfileBeforeActiveKeepsTheSameProfileSelected() {
        val updated = AppSettings(profiles = profiles, activeProfileIndex = 2).removeProfile(0)

        assertEquals(listOf("two", "three"), updated?.profiles?.map(VpnProfile::name))
        assertEquals(1, updated?.activeProfileIndex)
        assertEquals("three", updated?.profile?.name)
    }

    @Test
    fun removingActiveProfileSelectsItsNearestNeighbor() {
        val updated = AppSettings(profiles = profiles, activeProfileIndex = 2).removeProfile(2)

        assertEquals(listOf("one", "two"), updated?.profiles?.map(VpnProfile::name))
        assertEquals(1, updated?.activeProfileIndex)
        assertEquals("two", updated?.profile?.name)
    }

    @Test
    fun invalidIndexDoesNotChangeSettings() {
        assertNull(AppSettings(profiles = profiles).removeProfile(5))
    }

    @Test
    fun removingOnlyProfileResetsSelection() {
        val updated = AppSettings(profiles = profiles.take(1), activeProfileIndex = 0).removeProfile(0)

        assertEquals(emptyList(), updated?.profiles)
        assertEquals(0, updated?.activeProfileIndex)
        assertNull(updated?.profile)
    }

    @Test
    fun removingProfileAfterActiveKeepsActiveIndex() {
        val updated = AppSettings(profiles = profiles, activeProfileIndex = 0).removeProfile(2)

        assertEquals(listOf("one", "two"), updated?.profiles?.map(VpnProfile::name))
        assertEquals(0, updated?.activeProfileIndex)
        assertEquals("one", updated?.profile?.name)
    }
}
