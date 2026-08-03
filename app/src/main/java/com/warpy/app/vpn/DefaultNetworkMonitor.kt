package com.warpy.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress

internal interface NetworkObserver {
    fun start()
    fun stop()
    fun currentState(): PhysicalNetworkState? = null
}

internal data class PhysicalNetworkState(
    val network: Network,
    val capabilities: NetworkCapabilities,
    val linkProperties: LinkProperties?,
)

internal class DefaultNetworkMonitor(
    context: Context,
    private val onNetworkChanged: (PhysicalNetworkState?) -> Unit,
) : NetworkObserver {
    private val connectivity =
        context.getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager unavailable")

    @Volatile
    private var activeState: PhysicalNetworkState? = null
    private val candidates = linkedMapOf<Network, Candidate>()
    private val tracksBestNetworkOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Physical network available: $network")
            if (tracksBestNetworkOnly) candidates.clear()
            candidates.getOrPut(network, ::Candidate).apply {
                capabilities = connectivity.getNetworkCapabilities(network)
                linkProperties = connectivity.getLinkProperties(network)
            }
            publishBestNetwork()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            candidates.getOrPut(network, ::Candidate).capabilities = capabilities
            publishBestNetwork()
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            properties: LinkProperties,
        ) {
            candidates.getOrPut(network, ::Candidate).linkProperties = properties
            publishBestNetwork()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            candidates.getOrPut(network, ::Candidate).blocked = blocked
            publishBestNetwork()
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "Physical network lost: $network")
            candidates.remove(network)
            publishBestNetwork()
        }
    }

    private fun publishBestNetwork() {
        val currentNetwork = activeState?.network
        val nextState = candidates
            .mapNotNull { (network, candidate) ->
                val capabilities = candidate.capabilities ?: return@mapNotNull null
                val isSuspended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                if (!isHandoverCandidatePhysicalNetwork(
                        hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        isSuspended = isSuspended,
                        isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        isBlocked = candidate.blocked,
                    )
                ) {
                    return@mapNotNull null
                }
                PhysicalNetworkState(network, capabilities, candidate.linkProperties)
            }
            .maxByOrNull { state ->
                physicalNetworkPriority(
                    isValidated = state.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    hasEthernet = state.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    hasWifi = state.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    hasCellular = state.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    isMetered = !state.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    isCurrent = state.network == currentNetwork,
                )
            }

        val previousKey = activeState?.monitorKey()
        val nextKey = nextState?.monitorKey()
        activeState = nextState
        if (previousKey == nextKey) return

        if (nextState == null) {
            Log.i(TAG, "No physical internet network is available")
        } else {
            Log.i(
                TAG,
                "Selected physical network: ${nextState.network}, " +
                    "interface=${nextState.linkProperties?.interfaceName}",
            )
        }
        onNetworkChanged(nextState)
    }

    override fun start() {
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            if (tracksBestNetworkOnly) {
                connectivity.registerBestMatchingNetworkCallback(
                    request,
                    callback,
                    Handler(Looper.getMainLooper()),
                )
            } else {
                connectivity.registerNetworkCallback(request, callback)
            }
        }.onFailure {
            Log.e(TAG, "Failed to register physical network callback", it)
        }
    }

    override fun currentState(): PhysicalNetworkState? = activeState

    override fun stop() {
        runCatching {
            connectivity.unregisterNetworkCallback(callback)
        }.onFailure {
            Log.e(TAG, "Failed to unregister physical network callback", it)
        }
        activeState = null
        candidates.clear()
    }

    private data class Candidate(
        var capabilities: NetworkCapabilities? = null,
        var linkProperties: LinkProperties? = null,
        var blocked: Boolean = false,
    )

    private companion object {
        const val TAG = "DefaultNetworkMonitor"
    }
}

private data class MonitorStateKey(
    val networkHandle: Long,
    val interfaceName: String?,
    val dnsServers: List<String>,
    val isMetered: Boolean,
    val isValidated: Boolean,
)

private fun PhysicalNetworkState.monitorKey(): MonitorStateKey = MonitorStateKey(
    networkHandle = network.networkHandle,
    interfaceName = linkProperties?.interfaceName,
    dnsServers = linkProperties?.dnsServers.orEmpty().mapNotNull(InetAddress::getHostAddress),
    isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
)
