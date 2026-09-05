package com.warpy.app.vpn

import com.warpy.app.data.parseProfilesJson
import com.warpy.app.data.serializeProfilesJson
import com.warpy.app.model.VpnProfile
import java.io.File
import java.util.UUID

internal data class ProfileProbeRequest(
    val profiles: List<VpnProfile>,
    val indices: List<Int>,
    val id: String = UUID.randomUUID().toString(),
) {
    fun accepts(requestId: String, index: Int, currentProfiles: List<VpnProfile>): Boolean =
        requestId == id && index in indices &&
            profiles.getOrNull(index) == currentProfiles.getOrNull(index)

    fun writeSnapshot(directory: File) {
        snapshotFile(directory, id).writeText(serializeProfilesJson(profiles))
    }

    fun deleteSnapshot(directory: File) {
        snapshotFile(directory, id).delete()
    }

    companion object {
        fun readSnapshot(directory: File, id: String): List<VpnProfile> {
            val file = snapshotFile(directory, id)
            return try {
                parseProfilesJson(file.readText()).getOrThrow()
            } finally {
                file.delete()
            }
        }

        private fun snapshotFile(directory: File, id: String): File {
            require(UUID.fromString(id).toString() == id)
            return File(directory, "profile-probe-$id.json")
        }
    }
}
