package com.warpy.app.data

import com.warpy.app.model.VpnProfile
import java.net.URI
import java.net.URLDecoder

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
            if (profile.group.isNotBlank() && merged[existingIndex].group != profile.group) {
                merged[existingIndex] = merged[existingIndex].copy(group = profile.group)
            }
            existingIndex
        } else {
            merged += profile
            merged.lastIndex
        }
    }

    return MergedProfileImport(merged, importedIndex)
}

internal fun subscriptionDisplayName(value: String): String {
    val uri = URI(value.trim())
    val fragment = runCatching {
        URLDecoder.decode(uri.rawFragment.orEmpty(), Charsets.UTF_8.name())
    }.getOrDefault("").trim()
    if (fragment.isNotBlank() &&
        fragment.length <= 64 &&
        fragment.none { it.code in 0..31 || it.code == 127 }
    ) {
        return fragment
    }

    val parts = uri.host.orEmpty()
        .removePrefix("www.")
        .split('.')
        .filter(String::isNotBlank)
    val first = parts.firstOrNull().orEmpty()
    val genericPrefixes = setOf("api", "sub", "subs", "subscription", "panel")
    val tokenLike = first.matches(Regex("(?:[a-f0-9]{8,}|[0-9]{6,})", RegexOption.IGNORE_CASE))
    val label = if ((first.lowercase() in genericPrefixes || tokenLike) && parts.size > 1) {
        parts[1]
    } else {
        first
    }.replace(Regex("^with(?=[a-z0-9])", RegexOption.IGNORE_CASE), "")

    return label
        .ifBlank { "SUBSCRIPTION" }
        .replace(Regex("[-_]+"), " ")
        .uppercase()
}
