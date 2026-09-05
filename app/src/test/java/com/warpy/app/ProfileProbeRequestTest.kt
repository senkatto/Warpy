package com.warpy.app

import com.warpy.app.model.Protocol
import com.warpy.app.model.VpnProfile
import com.warpy.app.vpn.ProfileProbeRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProfileProbeRequestTest {
    private val first = VpnProfile("First", Protocol.Vless, "192.0.2.1", 443, uuid = "old")
    private val second = first.copy(name = "Second", server = "192.0.2.2")

    @Test
    fun `accepts only results for the current request and requested profile`() {
        val profiles = listOf(first, second)
        val request = ProfileProbeRequest(profiles, listOf(0))
        assertTrue(request.accepts(request.id, 0, profiles))
        assertFalse(request.accepts("old-request", 0, profiles))
        assertFalse(request.accepts(request.id, 1, profiles))
        assertFalse(request.accepts(request.id, -1, profiles))
        assertFalse(request.accepts(request.id, 2, profiles))
    }

    @Test
    fun `rejects shifted profiles and changed credentials`() {
        val request = ProfileProbeRequest(listOf(first, second), listOf(0, 1))
        assertFalse(request.accepts(request.id, 0, listOf(second)))
        assertFalse(request.accepts(request.id, 1, listOf(second)))
        assertFalse(request.accepts(request.id, 0, listOf(first.copy(uuid = "new"), second)))
    }

    @Test
    fun `each request reads its own fresh snapshot and removes credentials after reading`() {
        val directory = Files.createTempDirectory("warpy-probe-test").toFile()
        val old = ProfileProbeRequest(listOf(first), listOf(0))
        val fresh = ProfileProbeRequest(listOf(first.copy(uuid = "new"), second), listOf(0, 1))
        try {
            old.writeSnapshot(directory)
            fresh.writeSnapshot(directory)
            assertEquals(old.profiles, ProfileProbeRequest.readSnapshot(directory, old.id))
            assertEquals(fresh.profiles, ProfileProbeRequest.readSnapshot(directory, fresh.id))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertFailsWith<IllegalArgumentException> {
                ProfileProbeRequest.readSnapshot(directory, "../warpy")
            }
        } finally {
            old.deleteSnapshot(directory)
            fresh.deleteSnapshot(directory)
            directory.delete()
        }
    }
}
