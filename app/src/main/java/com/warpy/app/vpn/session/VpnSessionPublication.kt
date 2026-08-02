package com.warpy.app.vpn.session

import com.warpy.app.model.VpnState
import org.json.JSONObject

internal enum class PublishedVpnStatus(val wireValue: String) {
    Connecting("connecting"),
    Connected("connected"),
    Stopped("stopped"),
    Error("error"),
}

internal data class VpnSessionPublication(
    val generation: Long,
    val status: PublishedVpnStatus,
    val state: VpnState,
    val shouldRun: Boolean,
    val connectedAtElapsedMillis: Long,
    val runtimeProfileTag: String?,
) {
    val activeOutboundIndex: Int
        get() = runtimeProfileTag
            ?.removePrefix("profile_")
            ?.toIntOrNull()
            ?: -1
}

internal fun VpnSessionSnapshot.toPublication(): VpnSessionPublication =
    VpnSessionPublication(
        generation = generation,
        status = when (state) {
            VpnState.Stopped -> PublishedVpnStatus.Stopped
            VpnState.Starting,
            VpnState.Validating,
            VpnState.Recovering,
            VpnState.Stopping,
            -> PublishedVpnStatus.Connecting
            VpnState.Connected -> PublishedVpnStatus.Connected
            VpnState.Error -> PublishedVpnStatus.Error
        },
        state = state,
        shouldRun = shouldRun,
        connectedAtElapsedMillis = connectedAtElapsedMillis,
        runtimeProfileTag = runtimeProfileTag,
    )

internal object VpnSessionPublicationCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(publication: VpnSessionPublication): String =
        JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("generation", publication.generation)
            .put("status", publication.status.wireValue)
            .put("state", publication.state.label)
            .put("should_run", publication.shouldRun)
            .put("connected_at_elapsed", publication.connectedAtElapsedMillis)
            .put("runtime_profile_tag", publication.runtimeProfileTag ?: JSONObject.NULL)
            .toString()

    fun decode(encoded: String?): VpnSessionPublication? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(encoded)
            require(json.optInt("schema") == SCHEMA_VERSION)
            val status = PublishedVpnStatus.entries.first {
                it.wireValue == json.getString("status")
            }
            val state = VpnState.entries.first {
                it.label == json.getString("state")
            }
            VpnSessionPublication(
                generation = json.getLong("generation").coerceAtLeast(0L),
                status = status,
                state = state,
                shouldRun = json.getBoolean("should_run"),
                connectedAtElapsedMillis = json.getLong("connected_at_elapsed").coerceAtLeast(0L),
                runtimeProfileTag = if (json.isNull("runtime_profile_tag")) {
                    null
                } else {
                    json.getString("runtime_profile_tag").takeIf(String::isNotBlank)
                },
            )
        }.getOrNull()
    }
}
