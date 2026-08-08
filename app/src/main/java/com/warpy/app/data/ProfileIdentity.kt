package com.warpy.app.data

import com.warpy.app.model.VpnProfile

internal fun VpnProfile.connectionIdentity(): VpnProfile = copy(
    name = "",
    group = "",
    raw = "",
)

internal data class MergedProfileImport(
    val profiles: List<VpnProfile>,
    val importedIndex: Int,
)

internal fun mergeImportedProfiles(
    existing: List<VpnProfile>,
    imported: List<VpnProfile>,
): MergedProfileImport? {
    if (imported.isEmpty()) return null

    val merged = existing.toMutableList()
    var importedIndex = -1
    imported.forEach { profile ->
        val identity = profile.connectionIdentity()
        val existingIndex = merged.indexOfFirst { it.connectionIdentity() == identity }
        importedIndex = if (existingIndex >= 0) {
            existingIndex
        } else {
            merged += profile
            merged.lastIndex
        }
    }

    return MergedProfileImport(merged, importedIndex)
}
