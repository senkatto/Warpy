package com.warpy.app.vpn

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.hiddify.core.libbox.CommandServer
import com.hiddify.core.libbox.CommandServerHandler
import com.hiddify.core.libbox.ConnectionOwner
import com.hiddify.core.libbox.ExchangeContext
import com.hiddify.core.libbox.InterfaceUpdateListener
import com.hiddify.core.libbox.Libbox
import com.hiddify.core.libbox.LocalDNSTransport
import com.hiddify.core.libbox.NetworkInterfaceIterator
import com.hiddify.core.libbox.Notification
import com.hiddify.core.libbox.OverrideOptions
import com.hiddify.core.libbox.PlatformInterface
import com.hiddify.core.libbox.SetupOptions
import com.hiddify.core.libbox.StringIterator
import com.hiddify.core.libbox.SystemProxyStatus
import com.hiddify.core.libbox.TunOptions
import com.hiddify.core.libbox.WIFIState
import com.warpy.app.model.AppSettings
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.hiddify.core.libbox.NetworkInterface as BoxNetworkInterface

class ProfileProbeService : Service(), PlatformInterface, CommandServerHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: CommandServer? = null

    private fun setupCore() {
        val root = File(filesDir, "profile-probe").apply { mkdirs() }
        Libbox.setup(
            SetupOptions().apply {
                basePath = File(root, "base").apply { mkdirs() }.absolutePath
                workingPath = File(root, "working").apply { mkdirs() }.absolutePath
                tempPath = File(cacheDir, "profile-probe").apply { mkdirs() }.absolutePath
                fixAndroidStack = true
                commandServerListenPort = 0
                commandServerSecret = UUID.randomUUID().toString()
                logMaxLines = 100
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_PROBE) return START_NOT_STICKY
        val indices = intent.getIntArrayExtra(EXTRA_INDICES)?.toList().orEmpty()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return START_NOT_STICKY
        scope.launch { probeMutex.withLock { runProbe(startId, requestId, indices) } }
        return START_NOT_STICKY
    }

    private suspend fun runProbe(startId: Int, requestId: String, requestedIndices: List<Int>) {
        try {
            val settings = AppSettings(profiles = ProfileProbeRequest.readSnapshot(cacheDir, requestId))
            val indices = requestedIndices.filter { it in settings.profiles.indices }.distinct()
            if (indices.isEmpty()) return
            setupCore()
            val port = ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                socket.localPort
            }
            val secret = UUID.randomUUID().toString()
            val config = SingBoxConfigBuilder.buildProbe(settings, port, secret, indices)
            Libbox.checkConfig(config)
            val commandServer = CommandServer(this, this)
            server = commandServer
            commandServer.start()
            commandServer.startOrReloadService(config, OverrideOptions())

            val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
            coroutineScope {
                indices.map { index ->
                    async {
                        semaphore.withPermit {
                            val result = runCatching { ProfileLatencyProbe.measure(port, secret, index) }
                                .onFailure { if (it is CancellationException) throw it }
                                .onFailure { Log.w(TAG, "profile probe failed index=$index", it) }
                            publish(requestId, index, result.getOrNull())
                        }
                    }
                }.awaitAll()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "profile probe failed", error)
            requestedIndices.forEach { publish(requestId, it, null) }
        } finally {
            runCatching { server?.closeService() }
            runCatching { server?.close() }
            server = null
            finish(startId, requestId)
        }
    }

    private fun publish(requestId: String, index: Int, delayMillis: Int?) {
        sendBroadcast(
            Intent(ACTION_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_DELAY_MS, delayMillis ?: -1),
        )
    }

    private fun finish(startId: Int, requestId: String) {
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName).putExtra(EXTRA_REQUEST_ID, requestId))
        stopSelf(startId)
    }

    override fun onDestroy() {
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun openTun(options: TunOptions): Int = error("TUN is unavailable during profile checks")
    override fun autoDetectInterfaceControl(fd: Int) = Unit
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
    override fun useProcFS(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun localDNSTransport(): LocalDNSTransport = LocalDnsTransport
    override fun readWIFIState(): WIFIState? = null
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) = Unit
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) = Unit
    override fun sendNotification(notification: Notification?) = Unit
    override fun systemCertificates(): StringIterator = EmptyStringIterator
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner = error("Connection owner lookup is unavailable")
    override fun getInterfaces(): NetworkInterfaceIterator = EmptyNetworkInterfaceIterator
    override fun serviceStop() = Unit
    override fun serviceReload() = Unit
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun writeDebugMessage(message: String?) = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    private object EmptyStringIterator : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = false
        override fun next(): String = error("No values")
    }

    private object LocalDnsTransport : LocalDNSTransport {
        override fun raw(): Boolean = false
        override fun exchange(ctx: ExchangeContext, message: ByteArray) {
            error("Raw DNS is unavailable")
        }
        override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
            try {
                ctx.success(InetAddress.getAllByName(domain).mapNotNull { it.hostAddress }.joinToString("\n"))
            } catch (_: UnknownHostException) {
                ctx.errorCode(3)
            }
        }
    }

    private object EmptyNetworkInterfaceIterator : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): BoxNetworkInterface = error("No values")
    }

    companion object {
        private val probeMutex = Mutex()
        const val ACTION_PROBE = "com.warpy.app.PROBE_PROFILES"
        const val ACTION_RESULT = "com.warpy.app.PROFILE_PROBE_RESULT"
        const val ACTION_FINISHED = "com.warpy.app.PROFILE_PROBE_FINISHED"
        const val EXTRA_INDEX = "profile_index"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val EXTRA_INDICES = "profile_indices"
        const val EXTRA_REQUEST_ID = "request_id"
        private const val MAX_CONCURRENT_PROBES = 4
        private const val TAG = "WarpyProfileProbe"
    }
}
