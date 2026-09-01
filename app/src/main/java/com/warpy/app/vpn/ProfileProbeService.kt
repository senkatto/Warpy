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
import com.warpy.app.data.SettingsStore
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.hiddify.core.libbox.NetworkInterface as BoxNetworkInterface

class ProfileProbeService : Service(), PlatformInterface, CommandServerHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: CommandServer? = null

    override fun onCreate() {
        super.onCreate()
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
        scope.launch { runProbe(startId) }
        return START_NOT_STICKY
    }

    private suspend fun runProbe(startId: Int) {
        val settings = SettingsStore(this).load()
        if (settings.profiles.isEmpty()) {
            finish(startId)
            return
        }
        val port = ServerSocket().use { socket ->
            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            socket.localPort
        }
        val secret = UUID.randomUUID().toString()
        try {
            val config = SingBoxConfigBuilder.buildProbe(settings, port, secret)
            Libbox.checkConfig(config)
            val commandServer = CommandServer(this, this)
            server = commandServer
            commandServer.start()
            commandServer.startOrReloadService(config, OverrideOptions())

            val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
            settings.profiles.indices.map { index ->
                scope.async {
                    semaphore.withPermit {
                        val result = runCatching { probe(port, secret, index) }
                            .onFailure { Log.w(TAG, "profile probe failed index=$index", it) }
                        publish(index, result.getOrNull())
                    }
                }
            }.awaitAll()
        } catch (error: Exception) {
            Log.w(TAG, "profile probe failed", error)
            settings.profiles.indices.forEach { publish(it, null) }
        } finally {
            runCatching { server?.closeService() }
            runCatching { server?.close() }
            server = null
            finish(startId)
        }
    }

    private fun probe(port: Int, secret: String, index: Int): Int {
        val path = "/proxies/profile_$index/delay" +
            "?url=https%3A%2F%2Fwww.gstatic.com%2Fgenerate_204&timeout=4000"
        repeat(2) { attempt ->
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                    socket.soTimeout = 7_000
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                    writer.write("GET $path HTTP/1.1\r\n")
                    writer.write("Host: 127.0.0.1:$port\r\n")
                    writer.write("Authorization: Bearer $secret\r\n")
                    writer.write("Connection: close\r\n\r\n")
                    writer.flush()
                    val response = socket.getInputStream().bufferedReader().readText()
                    if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) {
                        error(response.lineSequence().firstOrNull().orEmpty())
                    }
                    return Regex("\\\"delay\\\"\\s*:\\s*(\\d+)")
                        .find(response)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?: error("empty delay")
                }
            } catch (error: Exception) {
                if (attempt > 0) throw error
                Thread.sleep(250)
            }
        }
        error("probe failed")
    }

    private fun publish(index: Int, delayMillis: Int?) {
        sendBroadcast(
            Intent(ACTION_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_DELAY_MS, delayMillis ?: -1),
        )
    }

    private fun finish(startId: Int) {
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName))
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
        const val ACTION_PROBE = "com.warpy.app.PROBE_PROFILES"
        const val ACTION_RESULT = "com.warpy.app.PROFILE_PROBE_RESULT"
        const val ACTION_FINISHED = "com.warpy.app.PROFILE_PROBE_FINISHED"
        const val EXTRA_INDEX = "profile_index"
        const val EXTRA_DELAY_MS = "delay_ms"
        private const val MAX_CONCURRENT_PROBES = 4
        private const val TAG = "WarpyProfileProbe"
    }
}
