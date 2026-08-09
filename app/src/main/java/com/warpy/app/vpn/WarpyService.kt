package com.warpy.app.vpn

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.LinkProperties
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.warpy.app.MainActivity
import com.warpy.app.data.SettingsStore
import com.warpy.app.localization.WarpyLocalization
import com.warpy.app.model.VpnState
import com.warpy.app.model.Protocol
import com.warpy.app.vpn.session.HttpTunnelValidator
import com.warpy.app.vpn.session.DnsEndpoint
import com.warpy.app.vpn.session.CoreGateway
import com.warpy.app.vpn.session.ConnectionRecoveryResult
import com.warpy.app.vpn.session.CancellableJobOwner
import com.warpy.app.vpn.session.DefaultCoreGateway
import com.warpy.app.vpn.session.ElapsedClock
import com.warpy.app.vpn.session.OutboundSwitchResult
import com.warpy.app.vpn.session.RecoveryRequest
import com.warpy.app.vpn.session.SessionValidationResult
import com.warpy.app.vpn.session.TunnelValidationRequest
import com.warpy.app.vpn.session.TunnelValidator
import com.warpy.app.vpn.session.UdpDnsExchanger
import com.warpy.app.vpn.session.ValidationReason
import com.warpy.app.vpn.session.VpnSessionEvent
import com.warpy.app.vpn.session.VpnSessionOperations
import com.warpy.app.vpn.session.VpnSessionReducer
import com.warpy.app.vpn.session.VpnSessionRuntime
import com.warpy.app.vpn.session.VpnSessionSnapshot
import com.warpy.app.vpn.session.VpnSessionPublication
import com.warpy.app.vpn.session.VpnSessionPublicationCodec
import com.warpy.app.vpn.session.PublishedVpnStatus
import com.warpy.app.vpn.session.toPublication
import com.hiddify.core.libbox.CommandClientHandler
import com.hiddify.core.libbox.CommandClientOptions
import com.hiddify.core.libbox.CommandServer
import com.hiddify.core.libbox.CommandServerHandler
import com.hiddify.core.libbox.ConnectionOwner
import com.hiddify.core.libbox.ConnectionEvents
import com.hiddify.core.libbox.ExchangeContext
import com.hiddify.core.libbox.InterfaceUpdateListener
import com.hiddify.core.libbox.Libbox
import com.hiddify.core.libbox.LocalDNSTransport
import com.hiddify.core.libbox.LogIterator
import com.hiddify.core.libbox.NetworkInterfaceIterator
import com.hiddify.core.libbox.OutboundGroupIterator
import com.hiddify.core.libbox.OverrideOptions
import com.hiddify.core.libbox.PlatformInterface
import com.hiddify.core.libbox.RoutePrefix
import com.hiddify.core.libbox.RoutePrefixIterator
import com.hiddify.core.libbox.SetupOptions
import com.hiddify.core.libbox.StatusMessage
import com.hiddify.core.libbox.StringIterator
import com.hiddify.core.libbox.SystemProxyStatus
import com.hiddify.core.libbox.TunOptions
import com.hiddify.core.libbox.WIFIState
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import com.hiddify.core.libbox.NetworkInterface as BoxNetworkInterface
import com.hiddify.core.libbox.Notification as BoxNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WarpyService : VpnService(), PlatformInterface, CommandServerHandler {
    private val stateMutex = Mutex()
    private val probeMutex = Mutex()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val coreResourceLock = Any()
    private val coreCloseLock = Any()
    private val publicationSideEffectLock = Any()
    @Volatile private var vpnState = VpnState.Stopped

    @Volatile private var commandServer: CommandServer? = null
    @Volatile private var statusClient: com.hiddify.core.libbox.CommandClient? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var upstreamNetwork: Network? = null
    private var interfaceListener: InterfaceUpdateListener? = null
    @Volatile private var localProxyConfig: LocalProxyConfig? = null
    @Volatile private var activeOutboundTag: String? = null
    @Volatile private var lastProbeFailure: String? = null
    private var lastUpstreamIdentity: UpstreamIdentity? = null
    private var stabilityMode = true
    @Volatile private var explicitStopRequested = false
    @Volatile private var closingCore = false
    @Volatile private var coreResourcesOpen = false
    @Volatile private var serviceDestroyed = false
    private var lastWakeProbeAt = 0L
    private var lastScreenOffAt = 0L
    private var networkMonitor: NetworkObserver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val wakeProbeJobOwner = CancellableJobOwner(serviceScope)
    private val tunnelWatchdogJobOwner = CancellableJobOwner(serviceScope)
    private val networkChangeJobOwner = CancellableJobOwner(serviceScope)
    @Volatile private var lastTunnelTrafficAt = 0L
    private val serviceHandler = Handler(Looper.getMainLooper())
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val dnsExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val secureRandom = SecureRandom()
    private val tunnelValidator: TunnelValidator = HttpTunnelValidator()
    private var sessionRuntime: VpnSessionRuntime? = null
    private var lastPublicationSideEffectKey: String? = null
    private val coreGateway: CoreGateway by lazy {
        DefaultCoreGateway(
            clientFactory = LibboxCoreCommandClientFactory(::TunnelStatusHandler),
            groupTag = PROXY_GROUP_TAG,
            clock = ElapsedClock(SystemClock::elapsedRealtime),
            shouldRetryHandshake = ::shouldRetryCommandHandshake,
            handshakeRetryDelayMillis = COMMAND_HANDSHAKE_RETRY_DELAY_MS,
        )
    }
    private val dnsExchanger = UdpDnsExchanger(
        protectSocket = { socket -> protect(socket) },
        timeoutMillis = DNS_TIMEOUT_MS,
    )

    override fun onCreate() {
        super.onCreate()
        setupLibbox()
        migrateLegacySavedConfig()
        ensureRuleSetExists()
        cancelKeepAlive()
    }

    private fun sessionRuntime(): VpnSessionRuntime = sessionRuntime ?: VpnSessionRuntime(
        scope = serviceScope,
        reducer = VpnSessionReducer(ElapsedClock(SystemClock::elapsedRealtime)),
        operations = ServiceSessionOperations(),
        onSnapshotChanged = ::publishSessionSnapshot,
    ).also { sessionRuntime = it }

    private inner class ServiceSessionOperations : VpnSessionOperations {
        override suspend fun startCore(generation: Long, profileTag: String) {
            try {
                stateMutex.withLock {
                    startCoreResources(profileTag)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException(humanizeStartError(error), error)
            }
        }

        override suspend fun stopCore(coreGeneration: Long) {
            stateMutex.withLock {
                val keepForeground = sessionRuntime?.snapshot()?.state in setOf(
                    VpnState.Starting,
                    VpnState.Recovering,
                )
                stopCoreInternalSync(removeForeground = !keepForeground)
            }
            if (sessionRuntime?.snapshot()?.state == VpnState.Error) {
                stopSelf()
            }
        }

        override suspend fun validateTunnel(
            generation: Long,
            reason: ValidationReason,
        ): SessionValidationResult = validateStartedCore(reason)

        override suspend fun switchOutbound(
            generation: Long,
            profileTag: String,
            previousRuntimeProfileTag: String,
        ): OutboundSwitchResult = switchOutboundAndValidate(
            generation = generation,
            profileTag = profileTag,
            previousRuntimeProfileTag = previousRuntimeProfileTag,
        )

        override suspend fun recoverConnection(
            generation: Long,
            request: RecoveryRequest,
        ): ConnectionRecoveryResult = recoverConnectionBounded(request)

        override fun cancelOperations(generation: Long) {
            cancelWakeProbeWork()
        }
    }

    private fun ensureRuleSetExists() {
        val target = File(filesDir, "warpy-ads.json")
        val temporary = File(filesDir, "warpy-ads.json.tmp")
        runCatching {
            val bundled = assets.open("warpy-ads.json").use { it.readBytes() }
            if (target.exists() && target.readBytes().contentEquals(bundled)) return
            temporary.writeBytes(bundled)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            Log.i(TAG, "Updated warpy-ads.json from app assets")
        }.onFailure {
            temporary.delete()
            Log.e(TAG, "Failed to update warpy-ads.json from assets", it)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SELECT_OUTBOUND -> {
                val tag = intent.getStringExtra(EXTRA_OUTBOUND_TAG).orEmpty()
                if (tag.isNotBlank()) {
                    cancelWakeProbeWork()
                    if (vpnState == VpnState.Connected && commandServer != null) {
                        serviceScope.launch {
                            sessionRuntime().dispatch(VpnSessionEvent.SwitchRequested(tag))
                        }
                    } else {
                        serviceScope.launch {
                            sessionRuntime().dispatch(VpnSessionEvent.StartRequested(tag))
                        }
                    }
                }
                return Service.START_STICKY
            }
            ACTION_STOP -> {
                explicitStopRequested = true
                if (hasCoreResources()) {
                    stopCore(clearSavedConfig = true)
                } else {
                    cancelWakeProbeWork()
                    saveLastConfig("")
                    saveShouldBeRunning(false)
                    cancelKeepAlive()
                    publishSessionSnapshot(VpnSessionSnapshot(), stopWhenStopped = false)
                    stopSelf(startId)
                }
                return Service.START_NOT_STICKY
            }
            ACTION_QUERY_STATUS -> {
                if (commandServer == null) {
                    val savedConfig = loadLastConfig()
                    if (loadShouldBeRunning() && savedConfig.isNotBlank()) {
                        stabilityMode = loadStabilityMode()
                        requestSessionStart(savedConfig)
                    } else {
                        publishSessionSnapshot(VpnSessionSnapshot(), stopWhenStopped = false)
                        stopSelf()
                    }
                } else {
                    sessionRuntime?.snapshot()?.let(::publishSessionSnapshot)
                }
            }
            ACTION_KEEPALIVE -> {
                // Migrate alarms scheduled by older builds. A healthy VpnService is
                // maintained by Android and network callbacks, not a wakeup alarm.
                cancelKeepAlive()
                explicitStopRequested = false
                val config = loadLastConfig()
                if (!loadShouldBeRunning() || config.isBlank()) {
                    stopSelf()
                } else {
                    startForeground()
                    if (commandServer == null && !explicitStopRequested) {
                        stabilityMode = loadStabilityMode()
                        requestSessionStart(config)
                    }
                }
            }
            else -> {
                cancelWakeProbeWork()
                if (intent == null && !loadShouldBeRunning()) {
                    publishSessionSnapshot(VpnSessionSnapshot(), stopWhenStopped = false)
                    stopSelf()
                    return Service.START_NOT_STICKY
                }
                explicitStopRequested = false
                val config = if (intent?.hasExtra(EXTRA_CONFIG) == true) {
                    intent.getStringExtra(EXTRA_CONFIG).orEmpty().also { saveLastConfig(it) }
                } else {
                    loadLastConfig()
                }
                if (config.isNotBlank()) saveShouldBeRunning(true)
                stabilityMode = intent?.getBooleanExtra(EXTRA_STABILITY_MODE, loadStabilityMode()) ?: loadStabilityMode()
                saveStabilityMode(stabilityMode)

                requestSessionStart(
                    config = config,
                    forceRestart = intent?.getBooleanExtra(EXTRA_FORCE_RESTART, false) == true,
                )
            }
        }
        return Service.START_STICKY
    }

    private fun selectOutboundSync(tag: String, logFailure: Boolean = true): Boolean {
        val result = coreGateway.selectOutbound(tag)
        if (!result.succeeded && logFailure) {
            Log.e(TAG, "failed to select outbound $tag", result.error)
        }
        return result.succeeded
    }

    private suspend fun selectOutboundWhenCommandReady(tag: String): Boolean {
        val result = coreGateway.selectOutboundWhenReady(tag)
        if (!result.succeeded) {
            Log.e(
                TAG,
                "sing-box command handshake failed after ${result.attempts} attempts",
                result.error,
            )
        }
        return result.succeeded
    }

    private fun closeTunnelConnections(reason: String): Boolean {
        val result = coreGateway.closeConnections()
        return if (result.succeeded) {
            Log.i(TAG, "closed stale tunnel connections reason=$reason")
            true
        } else {
            Log.w(TAG, "failed to close stale tunnel connections reason=$reason", result.error)
            false
        }
    }

    private suspend fun switchOutboundAndValidate(
        generation: Long,
        profileTag: String,
        previousRuntimeProfileTag: String,
    ): OutboundSwitchResult {
        ensureSessionOperationCurrent(generation)
        if (explicitStopRequested) {
            throw CancellationException("VPN stop requested")
        }

        val settings = com.warpy.app.data.SettingsStore(this).load()
        Log.i(
            TAG,
            "Attempting dynamic outbound switch: $previousRuntimeProfileTag -> $profileTag",
        )

        val switchSuccess = selectOutboundForSession(generation, profileTag)
        if (!switchSuccess) {
            Log.w(TAG, "Switch call failed, keeping previous active outbound")
            val previousIsValid = runConnectionProbe()
            ensureSessionOperationCurrent(generation)
            return if (previousIsValid &&
                persistSelectedProfileForSession(generation, previousRuntimeProfileTag)
            ) {
                OutboundSwitchResult.RolledBack("Не удалось выбрать новый профиль")
            } else {
                OutboundSwitchResult.Failed("Не удалось восстановить предыдущий профиль")
            }
        }

        val targetIndex = profileTag.removePrefix("profile_").toIntOrNull()
        if (settings.profiles.getOrNull(targetIndex ?: -1)?.protocol?.isUdpBased == true) {
            delay(HYSTERIA_RECOVERY_SETTLE_MS)
        }
        ensureSessionOperationCurrent(generation)
        val isValid = runConnectionProbe()
        ensureSessionOperationCurrent(generation)
        if (isValid) {
            if (persistSelectedProfileForSession(generation, profileTag)) {
                Log.i(TAG, "Dynamic switch validation success for outbound $profileTag")
                return OutboundSwitchResult.Succeeded(profileTag)
            }

            Log.e(TAG, "Selected outbound could not be persisted; rolling back")
            val rollbackSelected = selectOutboundForSession(generation, previousRuntimeProfileTag)
            val rollbackValid = rollbackSelected && runConnectionProbe()
            ensureSessionOperationCurrent(generation)
            return if (rollbackValid &&
                persistSelectedProfileForSession(generation, previousRuntimeProfileTag)
            ) {
                OutboundSwitchResult.RolledBack("Не удалось сохранить выбранный профиль")
            } else {
                OutboundSwitchResult.Failed("Не удалось восстановить профиль после ошибки сохранения")
            }
        }

        Log.w(
            TAG,
            "Dynamic switch validation failed for $profileTag, rolling back to $previousRuntimeProfileTag",
        )
        val rollbackSelected = selectOutboundForSession(generation, previousRuntimeProfileTag)
        val rollbackValid = rollbackSelected && runConnectionProbe()
        ensureSessionOperationCurrent(generation)
        return if (rollbackValid &&
            persistSelectedProfileForSession(generation, previousRuntimeProfileTag)
        ) {
            OutboundSwitchResult.RolledBack(
                "Не удалось подключиться к новому профилю",
            )
        } else {
            Log.e(TAG, "Outbound rollback failed; restarting the tunnel")
            OutboundSwitchResult.Failed(
                "Не удалось восстановить соединение после переключения профиля",
            )
        }
    }

    private suspend fun selectOutboundForSession(generation: Long, tag: String): Boolean =
        stateMutex.withLock {
            ensureSessionOperationCurrent(generation)
            if (commandServer == null) {
                Log.w(TAG, "command server is not running, cannot select outbound")
                return@withLock false
            }
            selectOutboundSync(tag).also { selected ->
                if (selected) setActiveOutboundTag(tag)
            }
        }

    private suspend fun persistSelectedProfileForSession(generation: Long, tag: String): Boolean =
        stateMutex.withLock {
            ensureSessionOperationCurrent(generation)
            persistSelectedProfile(tag)
        }

    private suspend fun ensureSessionOperationCurrent(generation: Long) {
        currentCoroutineContext().ensureActive()
        val snapshot = sessionRuntime?.snapshot()
        if (snapshot == null ||
            snapshot.generation != generation ||
            !snapshot.shouldRun ||
            snapshot.state == VpnState.Stopping ||
            snapshot.state == VpnState.Stopped
        ) {
            throw CancellationException("Stale VPN session operation")
        }
    }

    private fun persistSelectedProfile(tag: String): Boolean {
        val index = tag.removePrefix("profile_").toIntOrNull() ?: return false
        val store = com.warpy.app.data.SettingsStore(this)
        val currentSettings = store.load()
        if (index !in currentSettings.profiles.indices) return false
        val selectedSettings = currentSettings.copy(activeProfileIndex = index)
        val selectedConfig = runCatching {
            SingBoxConfigBuilder.build(selectedSettings, filesDir = filesDir.absolutePath)
        }.getOrElse { error ->
            Log.e(TAG, "failed to build persisted profile configuration", error)
            return false
        }
        if (!store.save(selectedSettings)) {
            Log.e(TAG, "failed to persist selected profile index=$index")
            return false
        }
        saveLastConfig(selectedConfig)
        return true
    }

    private suspend fun startCoreResources(requestedTag: String) {
        beginCoreResourceTransaction()
        try {
            val settings = com.warpy.app.data.SettingsStore(this@WarpyService).load()
            val requestedIndex = requestedTag.removePrefix("profile_").toIntOrNull()
            if (requestedIndex == null || settings.profiles.getOrNull(requestedIndex) == null) {
                throw IllegalArgumentException(MESSAGE_EMPTY_CONFIG)
            }
            currentCoroutineContext().ensureActive()
            startForeground()
            ensureCoreResourceTransactionOpen()
            upstreamNetwork = findUpstreamNetwork()
            lastUpstreamIdentity = upstreamNetwork?.let { network ->
                connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                    PhysicalNetworkState(
                        network = network,
                        capabilities = capabilities,
                        linkProperties = connectivity.getLinkProperties(network),
                    ).toIdentity()
                }
            }
            Log.i(TAG, "upstream network=${upstreamNetwork}")
            registerStabilityWatchersInternal()
            ensureCoreResourceTransactionOpen()

            val startedCore = startCommandServer(settings)
            currentCoroutineContext().ensureActive()
            ensureCoreResourceTransactionOpen()
            saveLastConfig(startedCore.config)
            Log.i(TAG, "sing-box configuration prepared")
            if (!selectOutboundWhenCommandReady(requestedTag)) {
                throw IllegalStateException("Не удалось выбрать активный профиль")
            }
            ensureCoreResourceTransactionOpen()
            setActiveOutboundTag(requestedTag)
        } catch (error: Throwable) {
            stopCoreInternalSync()
            throw error
        }
    }

    private suspend fun validateStartedCore(reason: ValidationReason): SessionValidationResult {
        val settings = com.warpy.app.data.SettingsStore(this@WarpyService).load()
        val preferredTag = sessionRuntime?.snapshot()?.preferredProfileTag
            ?: "profile_${settings.activeProfileIndex}"
        val preferredIndex = preferredTag.removePrefix("profile_").toIntOrNull()
            ?: settings.activeProfileIndex
        val preferredProfile = settings.profiles.getOrNull(preferredIndex)
        val runtimeTag = activeOutboundTag
            ?: sessionRuntime?.snapshot()?.runtimeProfileTag
            ?: preferredTag

        if (reason == ValidationReason.Initial && findUpstreamNetwork() == null) {
            return SessionValidationResult(
                succeeded = false,
                message = "Ожидание сети",
                recoverable = true,
            )
        }

        if (preferredProfile?.protocol?.isUdpBased == true &&
            runtimeTag == preferredTag &&
            reason in setOf(ValidationReason.Initial, ValidationReason.Recovery)
        ) {
            Log.i(TAG, "UDP profile selected — waiting 3s for the session before probe")
            delay(3_000)
        }

        val isValid = hasActiveVpnTunnel() && runConnectionProbe()

        return if (isValid) {
            startStatusUpdates()
            SessionValidationResult(succeeded = true)
        } else {
            val failure = if (reason == ValidationReason.Initial) {
                classifyInitialValidationFailure(
                    hasValidatedNetwork = findUpstreamNetwork() != null,
                    protocol = preferredProfile?.protocol,
                    probeFailure = lastProbeFailure,
                )
            } else {
                ValidationFailureDecision(
                    message = "Не удалось подтвердить обмен данными через VPN-туннель",
                    recoverable = true,
                )
            }
            SessionValidationResult(
                succeeded = false,
                message = failure.message,
                recoverable = failure.recoverable,
            )
        }
    }

    private suspend fun runConnectionProbe(
        maxRetries: Int = INITIAL_PROBE_RETRIES,
        connectTimeoutMillis: Int = INITIAL_PROBE_TIMEOUT_MS,
        readTimeoutMillis: Int = INITIAL_PROBE_TIMEOUT_MS,
    ): Boolean = probeMutex.withLock {
        val proxyConfig = localProxyConfig ?: return false
        lastProbeFailure = null
        val result = tunnelValidator.validate(
            TunnelValidationRequest(
                proxy = proxyConfig,
                maxAttempts = maxRetries,
                connectTimeoutMillis = connectTimeoutMillis,
                readTimeoutMillis = readTimeoutMillis,
            ),
        )
        result.attempts.forEachIndexed { index, attempt ->
            if (attempt.statusCode != null) {
                Log.i(TAG, "Honest probe response on attempt ${index + 1}, code=${attempt.statusCode}")
            } else if (attempt.failure != null) {
                Log.w(TAG, "Honest probe failed on attempt ${index + 1}: ${attempt.failure}")
            }
        }
        lastProbeFailure = result.lastFailure?.let(::stripAnsi)
        result.isValid
    }

    private fun stopCore(clearSavedConfig: Boolean = false) {
        cancelWakeProbeWork()
        if (clearSavedConfig) {
            saveLastConfig("")
            saveShouldBeRunning(false)
        }
        if (clearSavedConfig || explicitStopRequested) cancelKeepAlive()
        serviceScope.launch {
            sessionRuntime().dispatch(VpnSessionEvent.StopRequested)
        }
    }

    private fun requestSessionStart(
        config: String,
        forceRestart: Boolean = false,
    ) {
        val settings = com.warpy.app.data.SettingsStore(this).load()
        val requestedTag = "profile_${settings.activeProfileIndex}"
        if (config.isBlank() || settings.profile == null) {
            Log.e(TAG, "empty config")
        }
        serviceScope.launch {
            val event = if (forceRestart) {
                VpnSessionEvent.RestartRequested(requestedTag)
            } else {
                VpnSessionEvent.StartRequested(requestedTag)
            }
            sessionRuntime().dispatch(event)
        }
    }

    private data class CoreResourcesToClose(
        val statusClient: com.hiddify.core.libbox.CommandClient?,
        val commandServer: CommandServer?,
        val tun: ParcelFileDescriptor?,
        val screenReceiver: BroadcastReceiver?,
        val networkMonitor: NetworkObserver?,
    )

    private fun beginCoreResourceTransaction() {
        synchronized(coreResourceLock) {
            check(!serviceDestroyed) { "VPN service is shutting down" }
            check(
                !coreResourcesOpen &&
                    commandServer == null &&
                    statusClient == null &&
                    tun == null,
            ) { "VPN core resources are already active" }
            coreResourcesOpen = true
        }
    }

    private fun hasCoreResources(): Boolean = synchronized(coreResourceLock) {
        coreResourcesOpen ||
            commandServer != null ||
            statusClient != null ||
            tun != null ||
            screenReceiver != null ||
            networkMonitor != null
    }

    private fun ensureCoreResourceTransactionOpen() {
        check(
            synchronized(coreResourceLock) {
                coreResourcesOpen && !serviceDestroyed
            },
        ) { "VPN startup was cancelled" }
    }

    private fun claimCommandServer(server: CommandServer): Boolean =
        synchronized(coreResourceLock) {
            if (!coreResourcesOpen || serviceDestroyed || commandServer != null) {
                false
            } else {
                commandServer = server
                true
            }
        }

    private fun claimTun(descriptor: ParcelFileDescriptor): Boolean =
        synchronized(coreResourceLock) {
            if (!coreResourcesOpen || serviceDestroyed || tun != null) {
                false
            } else {
                tun = descriptor
                true
            }
        }

    private fun claimStatusClient(client: com.hiddify.core.libbox.CommandClient): Boolean =
        synchronized(coreResourceLock) {
            if (!coreResourcesOpen || serviceDestroyed || statusClient != null) {
                false
            } else {
                statusClient = client
                true
            }
        }

    private fun closeFailedCoreAttempt(server: CommandServer) {
        val resources = synchronized(coreResourceLock) {
            if (commandServer !== server) {
                null
            } else {
                commandServer = null
                localProxyConfig = null
                publishedLocalProxyConfig = null
                Pair(server, tun.also { tun = null })
            }
        } ?: return
        synchronized(coreCloseLock) {
            closingCore = true
            try {
                runCatching { resources.first.closeService() }
                runCatching { resources.first.close() }
                runCatching { resources.second?.close() }
            } finally {
                closingCore = false
            }
        }
    }

    private fun stopCoreInternalSync(removeForeground: Boolean = true) {
        wakeProbeJobOwner.cancel()
        tunnelWatchdogJobOwner.cancel()
        networkChangeJobOwner.cancel()
        val resources = synchronized(coreResourceLock) {
            coreResourcesOpen = false
            CoreResourcesToClose(
                statusClient = statusClient.also { statusClient = null },
                commandServer = commandServer.also { commandServer = null },
                tun = tun.also { tun = null },
                screenReceiver = screenReceiver.also { screenReceiver = null },
                networkMonitor = networkMonitor.also { networkMonitor = null },
            ).also {
                localProxyConfig = null
                publishedLocalProxyConfig = null
                interfaceListener = null
            }
        }
        synchronized(coreCloseLock) {
            closingCore = true
            try {
                runCatching { resources.statusClient?.disconnect() }
                runCatching { resources.commandServer?.closeService() }
                runCatching { resources.commandServer?.close() }
                runCatching { resources.tun?.close() }
            } finally {
                closingCore = false
            }
        }
        resources.screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        resources.networkMonitor?.stop()
        lastScreenOffAt = 0L
        upstreamNetwork = null
        lastUpstreamIdentity = null
        setActiveOutboundTag(null)
        if (removeForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun onPhysicalNetworkChanged(state: PhysicalNetworkState?) {
        networkChangeJobOwner.launch {
            delay(NETWORK_CHANGE_DEBOUNCE_MS)
            handlePhysicalNetworkChanged(state)
        }
    }

    private suspend fun handlePhysicalNetworkChanged(state: PhysicalNetworkState?) {
        if (vpnState != VpnState.Connected &&
            vpnState != VpnState.Recovering &&
            vpnState != VpnState.Validating
        ) return

        val identity = state?.toIdentity()
        if (identity != null && identity == lastUpstreamIdentity) {
            Log.d(TAG, "Duplicate validated network event for $identity. Ignoring.")
            return
        }

        if (state == null) {
            Log.i(TAG, "Validated physical network lost; waiting without tearing down the VPN")
            lastUpstreamIdentity = null
            upstreamNetwork = null
            runCatching { setUnderlyingNetworks(emptyArray()) }
            sessionRuntime().dispatch(
                VpnSessionEvent.UpstreamChanged(available = false),
            )
            return
        }

        Log.i(TAG, "Validated physical network changed to $identity")
        lastUpstreamIdentity = identity
        upstreamNetwork = state.network
        runCatching { setUnderlyingNetworks(arrayOf(state.network)) }
            .onFailure { Log.w(TAG, "failed to set VPN underlying network", it) }
        updateDefaultInterface(state.network)
        closeTunnelConnections("upstream-changed")
        sessionRuntime().dispatch(
            VpnSessionEvent.UpstreamChanged(available = true),
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed; foreground VPN service remains authoritative")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy called; cleaning up native resources")
        val preserveTerminalError = vpnState == VpnState.Error
        val lastSnapshot = sessionRuntime?.snapshot() ?: VpnSessionSnapshot()
        serviceDestroyed = true
        cancelWakeProbeWork()
        serviceHandler.removeCallbacksAndMessages(null)
        sessionRuntime?.close()
        sessionRuntime = null
        serviceScope.cancel()
        stopCoreInternalSync()
        if (!preserveTerminalError) {
            val finalSnapshot = if (!explicitStopRequested && loadShouldBeRunning()) {
                lastSnapshot.copy(
                    state = VpnState.Recovering,
                    shouldRun = true,
                    runtimeProfileTag = null,
                    connectedAtElapsedMillis = 0L,
                    lastError = null,
                )
            } else {
                VpnSessionSnapshot(
                    generation = lastSnapshot.generation,
                    state = VpnState.Stopped,
                    shouldRun = false,
                )
            }
            publishSessionSnapshot(finalSnapshot, stopWhenStopped = false)
        }
        dnsExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRevoke() {
        explicitStopRequested = true
        saveShouldBeRunning(false)
        stopCore(clearSavedConfig = true)
        super.onRevoke()
    }

    private fun setupLibbox() {
        val base = File(filesDir, "libbox").apply { mkdirs() }
        val working = File(filesDir, "working").apply { mkdirs() }
        val temp = File(cacheDir, "libbox").apply { mkdirs() }
        runCatching {
            Libbox.setup(
                SetupOptions().apply {
                    basePath = base.absolutePath
                    workingPath = working.absolutePath
                    tempPath = temp.absolutePath
                    fixAndroidStack = true
                    commandServerListenPort = 0
                    commandServerSecret = randomProxyCredential(24)
                    logMaxLines = 300
                },
            )
        }.onFailure {
            Log.e(TAG, "libbox setup failed", it)
        }
    }

    private data class StartedCore(
        val config: String,
    )

    private fun startCommandServer(settings: com.warpy.app.model.AppSettings): StartedCore {
        return LocalProxyStartupRetrier.start(
            maxAttempts = LOCAL_PROXY_BIND_ATTEMPTS,
            allocateProxy = ::createLocalProxyConfig,
            onBindConflict = {
                Log.w(TAG, "local proxy bind conflict; retrying with a new session port")
            },
        ) { proxyConfig ->
            val launchConfig = buildRuntimeConfig(settings, proxyConfig)
            val server = CommandServer(this@WarpyService, this@WarpyService)
            if (!claimCommandServer(server)) {
                synchronized(coreCloseLock) {
                    closingCore = true
                    try {
                        runCatching { server.close() }
                    } finally {
                        closingCore = false
                    }
                }
                error("VPN startup was cancelled")
            }
            try {
                Log.i(TAG, "checking sing-box config")
                Libbox.checkConfig(launchConfig)
                Log.i(TAG, "creating command server")
                server.start()
                Log.i(TAG, "starting sing-box service")
                server.startOrReloadService(launchConfig, OverrideOptions())
                activateLocalProxy(proxyConfig)
                StartedCore(launchConfig)
            } catch (error: Exception) {
                closeFailedCoreAttempt(server)
                throw error
            }
        }
    }

    private fun buildRuntimeConfig(
        settings: com.warpy.app.model.AppSettings,
        proxyConfig: LocalProxyConfig,
    ): String = SingBoxConfigBuilder.build(
        settings = settings,
        dynamicMtu = getOptimalMtu(),
        filesDir = filesDir.absolutePath,
        localProxy = proxyConfig,
    )

    private fun createLocalProxyConfig(): LocalProxyConfig {
        val port = ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            socket.localPort
        }
        return LocalProxyConfig(
            port = port,
            username = randomProxyCredential(12),
            password = randomProxyCredential(24),
        )
    }

    private fun randomProxyCredential(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun activateLocalProxy(config: LocalProxyConfig) {
        val accepted = synchronized(coreResourceLock) {
            if (!coreResourcesOpen || serviceDestroyed) {
                false
            } else {
                localProxyConfig = config
                publishedLocalProxyConfig = config
                true
            }
        }
        if (!accepted) error("VPN startup was cancelled")
        Log.i(TAG, "authenticated local proxy ready on a session-random port")
    }

    private fun deactivateLocalProxy() {
        synchronized(coreResourceLock) {
            localProxyConfig = null
            publishedLocalProxyConfig = null
        }
    }

    private fun getOptimalMtu(): Int {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return 1400
        val activeNet = findUpstreamNetwork() ?: return 1400
        val caps = connectivity.getNetworkCapabilities(activeNet) ?: return 1400
        return if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) 1280 else 1400
    }

    private fun humanizeStartError(error: Exception): String {
        val message = stripAnsi(error.message.orEmpty().ifBlank { error.toString() })
        val probeMessage = lastProbeFailure.orEmpty()
        return when {
            message.contains("health proxy", ignoreCase = true) ->
                "VPN не запустился: локальный порт проверки занят"
            isCommandChannelError(error) -> "VPN не запустился: внутренний сервис не ответил"
            probeMessage.contains("SOCKS server general failure", ignoreCase = true) ->
                "Профиль не подключился: сервер не принял Hysteria2 handshake; проверьте SNI, пароль и obfs"
            message.contains("x509", ignoreCase = true) ||
                message.contains("certificate is valid for", ignoreCase = true) ->
                "Профиль не подключился: TLS-сертификат не совпадает с SNI"
            message.contains("timeout", ignoreCase = true) -> "Профиль не подключился: сервер не отвечает"
            message.contains("connection refused", ignoreCase = true) -> "Профиль не подключился: сервер отклонил соединение"
            else -> "Профиль не подключился: ${message.take(180)}"
        }
    }

    private fun isCommandChannelError(error: Throwable): Boolean {
        val message = error.message.orEmpty().ifBlank { error.toString() }
        return message.contains("DeadlineExceeded", ignoreCase = true) &&
            message.contains("dial unix", ignoreCase = true) &&
            message.contains("/libbox/comm", ignoreCase = true)
    }

    private fun stripAnsi(message: String): String =
        message.replace(Regex("\\u001B\\[[;\\d]*m"), "")

    private fun cancelWakeProbeWork() {
        wakeProbeJobOwner.cancel()
    }

    private fun registerStabilityWatchersInternal() {
        if (stabilityMode && screenReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            lastScreenOffAt = SystemClock.elapsedRealtime()
                        }
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                            val now = SystemClock.elapsedRealtime()
                            val screenOffMillis = lastScreenOffAt
                                .takeIf { it > 0L }
                                ?.let { (now - it).coerceAtLeast(0L) }
                                ?: 0L
                            lastScreenOffAt = 0L
                            scheduleWakeConnectionProbeInternal("screen", screenOffMillis)
                        }
                    }
                }
            }
            val accepted = synchronized(coreResourceLock) {
                if (coreResourcesOpen && !serviceDestroyed && screenReceiver == null) {
                    screenReceiver = receiver
                    true
                } else {
                    false
                }
            }
            if (accepted) {
                try {
                    registerReceiver(
                        receiver,
                        IntentFilter().apply {
                            addAction(Intent.ACTION_SCREEN_OFF)
                            addAction(Intent.ACTION_SCREEN_ON)
                            addAction(Intent.ACTION_USER_PRESENT)
                        },
                    )
                } catch (error: Exception) {
                    synchronized(coreResourceLock) {
                        if (screenReceiver === receiver) screenReceiver = null
                    }
                    throw error
                }
                val stillOwned = synchronized(coreResourceLock) {
                    coreResourcesOpen && !serviceDestroyed && screenReceiver === receiver
                }
                if (!stillOwned) {
                    runCatching { unregisterReceiver(receiver) }
                }
            }
        }
        if (networkMonitor == null) {
            val monitor = DefaultNetworkMonitor(this, ::onPhysicalNetworkChanged)
            val accepted = synchronized(coreResourceLock) {
                if (coreResourcesOpen && !serviceDestroyed && networkMonitor == null) {
                    networkMonitor = monitor
                    true
                } else {
                    false
                }
            }
            if (accepted) {
                try {
                    monitor.start()
                } catch (error: Exception) {
                    synchronized(coreResourceLock) {
                        if (networkMonitor === monitor) networkMonitor = null
                    }
                    throw error
                }
                val stillOwned = synchronized(coreResourceLock) {
                    coreResourcesOpen && !serviceDestroyed && networkMonitor === monitor
                }
                if (!stillOwned) {
                    monitor.stop()
                }
            }
        }
    }

    private fun scheduleWakeConnectionProbeInternal(reason: String, screenOffMillis: Long) {
        val now = SystemClock.elapsedRealtime()
        if (!stabilityMode ||
            commandServer == null ||
            vpnState != VpnState.Connected ||
            now - lastWakeProbeAt < WAKE_PROBE_MIN_INTERVAL_MS
        ) return
        lastWakeProbeAt = now
        wakeProbeJobOwner.launch {
            delay(WAKE_PROBE_DELAY_MS)
            if (commandServer == null || vpnState != VpnState.Connected) return@launch

            val currentNetwork = findUpstreamNetwork()
            if (currentNetwork == null) {
                Log.i(TAG, "Skipping wake probe until Android validates the physical network")
                return@launch
            }
            upstreamNetwork = currentNetwork
            updateDefaultInterface(currentNetwork)

            Log.i(TAG, "checking tunnel after wake reason=$reason")
            val isValid = runConnectionProbe(
                maxRetries = WAKE_PROBE_RETRIES,
                connectTimeoutMillis = WAKE_PROBE_TIMEOUT_MS,
                readTimeoutMillis = WAKE_PROBE_TIMEOUT_MS,
            )
            if (isValid) {
                Log.i(TAG, "wake tunnel check succeeded")
                if (shouldResetConnectionsAfterSleep(screenOffMillis)) {
                    closeTunnelConnections("$reason/after-${screenOffMillis}ms-sleep")
                }
            } else {
                Log.w(TAG, "wake tunnel check failed; scheduling bounded recovery")
                val runtime = sessionRuntime()
                runtime.dispatch(
                    VpnSessionEvent.RecoveryRequested(
                        generation = runtime.snapshot().generation,
                        request = RecoveryRequest(
                            reason = "$reason/wake",
                            probeBeforeRefresh = false,
                            resetConnectionsOnSuccess = true,
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun recoverConnectionBounded(
        request: RecoveryRequest,
    ): ConnectionRecoveryResult {
        val startedAt = SystemClock.elapsedRealtime()
        var failedAttempt = 0
        var probeCurrentCore = request.probeBeforeRefresh

        while (currentCoroutineContext().isActive &&
            !explicitStopRequested &&
            loadShouldBeRunning() &&
            shouldContinueRecovery(
                failedAttempts = failedAttempt,
                elapsedMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
            )
        ) {
            val currentNetwork = findUpstreamNetwork()
                ?: return ConnectionRecoveryResult.Deferred("Ожидание сети")

            upstreamNetwork = currentNetwork
            runCatching { setUnderlyingNetworks(arrayOf(currentNetwork)) }
                .onFailure { Log.w(TAG, "failed to update underlying network", it) }
            updateDefaultInterface(currentNetwork)

            if (probeCurrentCore && hasActiveVpnTunnel() && probeForRecovery()) {
                return completedRecovery(request)
            }
            probeCurrentCore = false

            val retryDelay = recoveryDelayMillis(
                failedAttempt = failedAttempt,
                jitterUnit = secureRandom.nextDouble(),
            )
            if (retryDelay > 0L) delay(retryDelay)
            currentCoroutineContext().ensureActive()
            if (explicitStopRequested || !loadShouldBeRunning()) {
                throw CancellationException("VPN stop requested")
            }

            val refreshed = refreshCoreForRecovery(request.reason, failedAttempt + 1)
            if (refreshed && activeProfileProtocol()?.isUdpBased == true) {
                delay(HYSTERIA_RECOVERY_SETTLE_MS)
            }
            if (refreshed && hasActiveVpnTunnel() && probeForRecovery()) {
                return completedRecovery(request)
            }
            failedAttempt += 1
        }

        Log.w(
            TAG,
            "bounded recovery exhausted reason=${request.reason}",
        )
        return ConnectionRecoveryResult.Exhausted("Не удалось установить соединение")
    }

    private fun completedRecovery(request: RecoveryRequest): ConnectionRecoveryResult.Succeeded {
        check(hasActiveVpnTunnel()) { "Android VPN tunnel is unavailable after recovery" }
        if (request.resetConnectionsOnSuccess) {
            closeTunnelConnections("${request.reason}/recovered")
        }
        startStatusUpdates()
        val runtimeTag = activeOutboundTag
            ?: sessionRuntime?.snapshot()?.runtimeProfileTag
            ?: sessionRuntime?.snapshot()?.preferredProfileTag
            ?: error("Active outbound is unavailable after recovery")
        return ConnectionRecoveryResult.Succeeded(runtimeTag)
    }

    private fun hasActiveVpnTunnel(): Boolean {
        val descriptorValid = synchronized(coreResourceLock) {
            tun?.let { descriptor ->
                runCatching {
                    descriptor.fd >= 0 && descriptor.fileDescriptor.valid()
                }.getOrDefault(false)
            } == true
        }
        if (!descriptorValid) return false

        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                !connectivity.getLinkProperties(network)?.interfaceName.isNullOrBlank()
        }
    }

    private suspend fun probeForRecovery(): Boolean = runConnectionProbe(
        maxRetries = RECOVERY_PROBE_RETRIES,
        connectTimeoutMillis = RECOVERY_PROBE_TIMEOUT_MS,
        readTimeoutMillis = RECOVERY_PROBE_TIMEOUT_MS,
    )

    private suspend fun refreshCoreForRecovery(reason: String, attempt: Int): Boolean =
        stateMutex.withLock {
            if (explicitStopRequested || !loadShouldBeRunning()) return@withLock false
            val savedConfig = loadLastConfig()
            val server = commandServer
            val proxyConfig = localProxyConfig
            if (savedConfig.isBlank() || server == null || proxyConfig == null) return@withLock false

            runCatching {
                Log.i(TAG, "refreshing sing-box reason=$reason attempt=$attempt")
                val settings = com.warpy.app.data.SettingsStore(this@WarpyService).load()
                val config = buildRuntimeConfig(settings, proxyConfig)
                server.startOrReloadService(config, OverrideOptions())
                val selectedTag = activeOutboundTag ?: "profile_${settings.activeProfileIndex}"
                check(selectOutboundSync(selectedTag)) { "failed to restore selected outbound" }
                setActiveOutboundTag(selectedTag)
                true
            }.getOrElse {
                Log.w(TAG, "sing-box recovery refresh failed reason=$reason attempt=$attempt", it)
                false
            }
        }

    private fun activeProfileProtocol(): Protocol? {
        val settings = com.warpy.app.data.SettingsStore(this).load()
        val tag = activeOutboundTag ?: "profile_${settings.activeProfileIndex}"
        return profileProtocol(tag, settings)
    }

    private fun profileProtocol(
        tag: String,
        settings: com.warpy.app.model.AppSettings =
            com.warpy.app.data.SettingsStore(this).load(),
    ): Protocol? {
        val index = tag.substringAfter("profile_").toIntOrNull() ?: return null
        return settings.profiles.getOrNull(index)?.protocol
    }

    private fun recoverUnexpectedCoreStop() {
        val config = loadLastConfig()
        if (!loadShouldBeRunning() || config.isBlank()) {
            stopCore()
            return
        }

        if (explicitStopRequested || prepare(this) != null) return
        Log.i(TAG, "recovering after unexpected core stop")
        serviceScope.launch {
            val runtime = sessionRuntime()
            runtime.dispatch(
                VpnSessionEvent.CoreDied(
                    generation = runtime.snapshot().generation,
                    message = "sing-box core stopped",
                ),
            )
        }
    }

    private fun saveLastConfig(config: String) {
        val saved = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONFIG_SAVED, config.isNotBlank())
            .remove(KEY_LAST_CONFIG)
            .commit()
        if (!saved) {
            Log.w(TAG, "failed to persist saved-config state")
        }
    }

    private fun loadLastConfig(): String {
        val prefs = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CONFIG_SAVED, false)) return ""
        val settings = com.warpy.app.data.SettingsStore(this).load()
        return if (settings.profile == null) "" else runCatching {
            SingBoxConfigBuilder.build(settings, filesDir = filesDir.absolutePath)
        }.getOrDefault("")
    }

    private fun migrateLegacySavedConfig() {
        val prefs = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
        val legacyConfig = prefs.getString(KEY_LAST_CONFIG, null)
        if (!prefs.contains(KEY_CONFIG_SAVED)) {
            prefs.edit()
                .putBoolean(KEY_CONFIG_SAVED, !legacyConfig.isNullOrBlank())
                .remove(KEY_LAST_CONFIG)
                .apply()
        } else if (legacyConfig != null) {
            prefs.edit().remove(KEY_LAST_CONFIG).apply()
        }
    }

    private fun saveShouldBeRunning(enabled: Boolean) {
        val saved = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOULD_BE_RUNNING, enabled)
            .commit()
        if (!saved) {
            Log.w(TAG, "failed to persist desired VPN state")
        }
    }

    private fun loadShouldBeRunning(): Boolean =
        getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE).getBoolean(KEY_SHOULD_BE_RUNNING, false)

    private fun cancelKeepAlive() {
        val intent = Intent(this, WarpyService::class.java).setAction(ACTION_KEEPALIVE)
        val pendingIntent =
            PendingIntent.getForegroundService(this, KEEPALIVE_REQUEST_CODE, intent, keepAlivePendingIntentFlags())
        getSystemService(AlarmManager::class.java).cancel(pendingIntent)
    }

    private fun keepAlivePendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun saveStabilityMode(enabled: Boolean) {
        val saved = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STABILITY_MODE, enabled)
            .commit()
        if (!saved) {
            Log.w(TAG, "failed to persist stability mode")
        }
    }

    private fun loadStabilityMode(): Boolean =
        getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE).getBoolean(KEY_STABILITY_MODE, true)

    private fun startForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Warpy", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = buildNotification("VPN работает")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun buildNotification(message: String): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.warpy.app.R.mipmap.ic_launcher)
            .setContentTitle("Warpy")
            .setContentText(localizedServiceText(message))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun localizedServiceText(message: String): String = runCatching {
        WarpyLocalization.text(message, SettingsStore(this).load().language)
    }.getOrDefault(message)

    @SuppressLint("NewApi")
    override fun openTun(options: TunOptions): Int {
        Log.i(TAG, "openTun mtu=${options.mtu} autoRoute=${options.autoRoute}")
        if (prepare(this) != null) error("android: missing vpn permission")
        val builder = Builder()
            .setSession("Warpy")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // false inherits meteredness from the configured underlying network.
            builder.setMetered(false)
        }

        options.inet4Address.forEachRoute { builder.addAddress(it.address(), it.prefix()) }
        var hasV6Address = false
        options.inet6Address.forEachRoute {
            hasV6Address = true
            builder.addAddress(it.address(), it.prefix())
        }

        if (options.autoRoute) {
            builder.addDnsServer(options.dnsServerAddress.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var hasV4Route = false
                options.inet4RouteAddress.forEachRoute {
                    hasV4Route = true
                    builder.addRoute(it.toIpPrefix())
                }
                if (!hasV4Route) builder.addRoute("0.0.0.0", 0)

                if (hasV6Address) {
                    var hasV6Route = false
                    options.inet6RouteAddress.forEachRoute {
                        hasV6Route = true
                        builder.addRoute(it.toIpPrefix())
                    }
                    if (!hasV6Route) builder.addRoute("::", 0)
                }

                options.inet4RouteExcludeAddress.forEachRoute { builder.excludeRoute(it.toIpPrefix()) }
                if (hasV6Address) {
                    options.inet6RouteExcludeAddress.forEachRoute { builder.excludeRoute(it.toIpPrefix()) }
                }
            } else {
                options.inet4RouteRange.forEachRoute { builder.addRoute(it.address(), it.prefix()) }
                if (hasV6Address) {
                    options.inet6RouteRange.forEachRoute { builder.addRoute(it.address(), it.prefix()) }
                }
            }

            val includePackages = options.includePackage.toList()
                .filter { it != packageName }
                .distinct()
            val excludePackages = options.excludePackage.toList()
                .filter { it != packageName }
                .distinct()

            if (includePackages.isNotEmpty()) {
                includePackages.forEach {
                    runCatching { builder.addAllowedApplication(it) }
                        .onFailure { error -> if (error is NameNotFoundException) Log.w(TAG, "missing app $it") }
                }
            } else {
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { error -> Log.w(TAG, "cannot exclude own package from VPN", error) }
                excludePackages.forEach {
                    runCatching { builder.addDisallowedApplication(it) }
                        .onFailure { error -> if (error is NameNotFoundException) Log.w(TAG, "missing app $it") }
                }
            }
        }

        val fd = builder.establish() ?: error("android: establish VPN failed")
        if (!claimTun(fd)) {
            runCatching { fd.close() }
            error("android: VPN startup was cancelled")
        }
        upstreamNetwork?.let { network ->
            runCatching { setUnderlyingNetworks(arrayOf(network)) }
                .onFailure { Log.w(TAG, "failed to set initial VPN underlying network", it) }
        }
        Log.i(TAG, "openTun established fd=${fd.fd}")
        return fd.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun localDNSTransport(): LocalDNSTransport = AndroidLocalDnsTransport()
    override fun readWIFIState(): WIFIState? = null
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        synchronized(coreResourceLock) {
            if (coreResourcesOpen && !serviceDestroyed) {
                interfaceListener = listener
            }
        }
        updateDefaultInterface()
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        synchronized(coreResourceLock) {
            if (interfaceListener === listener) {
                interfaceListener = null
            }
        }
    }
    override fun sendNotification(notification: BoxNotification?) = Unit
    override fun systemCertificates(): StringIterator {
        val certificates = mutableListOf<String>()
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidCAStore")
            keyStore.load(null, null)
            val aliases = keyStore.aliases()
            val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
            while (aliases.hasMoreElements()) {
                val certificate = keyStore.getCertificate(aliases.nextElement()) ?: continue
                certificates += "-----BEGIN CERTIFICATE-----\n" +
                    encoder.encodeToString(certificate.encoded) +
                    "\n-----END CERTIFICATE-----"
            }
        }.onFailure {
            Log.w(TAG, "failed to load Android system certificates", it)
        }
        return StringArray(certificates)
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner = throw Exception("connection owner lookup unavailable")

    override fun getInterfaces(): NetworkInterfaceIterator {
        val networks = connectivity.allNetworks
        val javaInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val result = mutableListOf<BoxNetworkInterface>()
        for (network in networks) {
            val link = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val name = link.interfaceName ?: continue
            val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: continue
            result += BoxNetworkInterface().apply {
                this.name = name
                index = javaInterface.index
                mtu = runCatching { javaInterface.mtu }.getOrDefault(1500)
                dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') })
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                addresses = StringArray(javaInterface.interfaceAddresses.mapNotNull {
                    val host = it.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                    "$host/${it.networkPrefixLength}"
                })
                var interfaceFlags = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    interfaceFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (javaInterface.isLoopback) interfaceFlags = interfaceFlags or OsConstants.IFF_LOOPBACK
                if (javaInterface.isPointToPoint) interfaceFlags = interfaceFlags or OsConstants.IFF_POINTOPOINT
                if (javaInterface.supportsMulticast()) interfaceFlags = interfaceFlags or OsConstants.IFF_MULTICAST
                flags = interfaceFlags
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return NetworkInterfaceArray(result.iterator())
    }

    private fun publishSessionSnapshot(snapshot: VpnSessionSnapshot) {
        publishSessionSnapshot(snapshot, stopWhenStopped = true)
    }

    private fun publishSessionSnapshot(
        snapshot: VpnSessionSnapshot,
        stopWhenStopped: Boolean,
    ) {
        vpnState = snapshot.state
        activeOutboundTag = snapshot.runtimeProfileTag ?: activeOutboundTag
        val message = when (snapshot.state) {
            VpnState.Stopped -> MESSAGE_STOPPED
            VpnState.Starting -> MESSAGE_STARTING
            VpnState.Validating -> MESSAGE_CHECKING_PROFILE
            VpnState.Connected -> snapshot.lastError ?: MESSAGE_CONNECTED
            VpnState.Recovering -> MESSAGE_RESTORING
            VpnState.Stopping -> "Остановка VPN"
            VpnState.Error -> snapshot.lastError ?: "Не удалось подключиться"
        }
        publishStatus(snapshot.toPublication(), message)
        if (stopWhenStopped && snapshot.state == VpnState.Stopped) {
            stopSelf()
        }
    }

    private fun publishStatus(
        publication: VpnSessionPublication,
        message: String,
    ) {
        val encoded = VpnSessionPublicationCodec.encode(publication)
        val stored = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSION_PUBLICATION, encoded)
            .putBoolean(KEY_SHOULD_BE_RUNNING, publication.shouldRun)
            .remove(KEY_CURRENT_STATUS)
            .remove(KEY_CONNECTED_AT_ELAPSED)
            .remove(KEY_ACTIVE_OUTBOUND_TAG)
            .commit()
        if (!stored) {
            Log.w(TAG, "failed to persist session publication")
        }
        val sideEffectKey = "$encoded\u0000$message"
        val stateChanged = synchronized(publicationSideEffectLock) {
            if (lastPublicationSideEffectKey == sideEffectKey) {
                false
            } else {
                lastPublicationSideEffectKey = sideEffectKey
                true
            }
        }
        if (stateChanged) {
            saveStatusEvent(publication.status.wireValue, message)
            updateForegroundMessage(message)
        }
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, publication.status.wireValue)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_CONNECTED_AT_ELAPSED, publication.connectedAtElapsedMillis)
                .putExtra(EXTRA_ACTIVE_OUTBOUND_INDEX, publication.activeOutboundIndex)
                .putExtra("vpn_state", publication.state.label),
        )
    }

    private fun startStatusUpdates() {
        stopStatusUpdates()
        val client = Libbox.newCommandClient(
            TunnelStatusHandler(),
            CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                statusInterval = STATUS_INTERVAL_NANOS
            },
        )
        runCatching {
            client.connect()
            if (!claimStatusClient(client)) {
                runCatching { client.disconnect() }
                return
            }
        }.onFailure { error ->
            runCatching { client.disconnect() }
            Log.w(TAG, "sing-box traffic statistics are unavailable", error)
        }
        startTunnelWatchdog()
    }

    private fun stopStatusUpdates() {
        tunnelWatchdogJobOwner.cancel()
        val client = synchronized(coreResourceLock) {
            statusClient.also { statusClient = null }
        }
        runCatching { client?.disconnect() }
    }

    private fun startTunnelWatchdog() {
        if (!stabilityMode) return
        lastTunnelTrafficAt = SystemClock.elapsedRealtime()
        tunnelWatchdogJobOwner.launch {
            var consecutiveFailures = 0
            while (currentCoroutineContext().isActive) {
                delay(TUNNEL_WATCHDOG_INTERVAL_MS)
                if (serviceDestroyed ||
                    !loadShouldBeRunning() ||
                    vpnState != VpnState.Connected ||
                    commandServer == null
                ) {
                    consecutiveFailures = 0
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastTunnelTrafficAt < TUNNEL_WATCHDOG_INTERVAL_MS) {
                    consecutiveFailures = 0
                    continue
                }
                if (findUpstreamNetwork() == null) {
                    consecutiveFailures = 0
                    continue
                }

                val isValid = runConnectionProbe(
                    maxRetries = TUNNEL_WATCHDOG_PROBE_RETRIES,
                    connectTimeoutMillis = TUNNEL_WATCHDOG_PROBE_TIMEOUT_MS,
                    readTimeoutMillis = TUNNEL_WATCHDOG_PROBE_TIMEOUT_MS,
                )
                if (isValid) {
                    consecutiveFailures = 0
                    continue
                }

                consecutiveFailures += 1
                Log.w(TAG, "background tunnel probe failed ($consecutiveFailures/$TUNNEL_WATCHDOG_FAILURES)")
                if (consecutiveFailures < TUNNEL_WATCHDOG_FAILURES) continue

                val runtime = sessionRuntime()
                val snapshot = runtime.snapshot()
                if (snapshot.state == VpnState.Connected && snapshot.shouldRun) {
                    runtime.dispatch(
                        VpnSessionEvent.RecoveryRequested(
                            generation = snapshot.generation,
                            request = RecoveryRequest(
                                reason = "background-watchdog",
                                probeBeforeRefresh = false,
                                resetConnectionsOnSuccess = true,
                            ),
                        ),
                    )
                }
                return@launch
            }
        }
    }

    private inner class TunnelStatusHandler : CommandClientHandler {
        override fun connected() = Unit
        override fun disconnected(message: String?) = Unit
        override fun clearLogs() = Unit
        override fun initializeClashMode(modes: StringIterator?, currentMode: String?) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(mode: String?) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
        override fun writeGroups(groups: OutboundGroupIterator?) = Unit
        override fun writeLogs(logs: LogIterator?) = Unit
        override fun writeStatus(message: StatusMessage?) {
            if (message == null) return
            if (message.downlink > 0L || message.uplink > 0L) {
                lastTunnelTrafficAt = SystemClock.elapsedRealtime()
            }
            sendBroadcast(
                Intent(ACTION_STATS)
                    .setPackage(packageName)
                    .putExtra(EXTRA_RX_SPEED, message.downlink.coerceAtLeast(0L))
                    .putExtra(EXTRA_TX_SPEED, message.uplink.coerceAtLeast(0L)),
            )
        }
    }

    private fun setActiveOutboundTag(tag: String?) {
        activeOutboundTag = tag
    }

    private fun saveStatusEvent(status: String, message: String) {
        val prefs = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
        val event = "${System.currentTimeMillis()}|$status|${message.replace('\n', ' ').take(220)}"
        val events = (prefs.getString(KEY_STATUS_EVENTS, "").orEmpty().lineSequence().filter { it.isNotBlank() } + event)
            .toList()
            .takeLast(MAX_STATUS_EVENTS)
            .joinToString("\n")
        prefs.edit().putString(KEY_STATUS_EVENTS, events).apply()
    }

    private fun updateForegroundMessage(message: String) {
        if (commandServer == null && tun == null) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun serviceStop() {
        if (closingCore) return
        if (explicitStopRequested || prepare(this) != null) {
            stopCore()
        } else {
            recoverUnexpectedCoreStop()
        }
    }

    override fun serviceReload() = Unit
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message.orEmpty())
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    private inner class AndroidLocalDnsTransport : LocalDNSTransport {
        override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        override fun exchange(ctx: ExchangeContext, message: ByteArray) {
            val network = upstreamNetwork ?: findUpstreamNetwork() ?: error("missing upstream network")
            val endpoints = upstreamDnsServers(network).map(::DnsEndpoint)
            ctx.rawSuccess(dnsExchanger.exchange(message, endpoints))
        }

        override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
            val upstream = upstreamNetwork ?: findUpstreamNetwork() ?: error("missing upstream network")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val latch = CountDownLatch(1)
                var failure: Throwable? = null
                val signal = CancellationSignal()
                ctx.onCancel(signal::cancel)
                val queryType = when {
                    network.endsWith("4") -> DnsResolver.TYPE_A
                    network.endsWith("6") -> DnsResolver.TYPE_AAAA
                    else -> null
                }
                val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                    override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                        if (rcode == 0) {
                            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                        } else {
                            ctx.errorCode(rcode)
                        }
                        latch.countDown()
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        Log.w(TAG, "local DNS lookup error domain=$domain", error)
                        val cause = error.cause
                        if (cause is ErrnoException) ctx.errnoCode(cause.errno) else failure = error
                        latch.countDown()
                    }
                }
                if (queryType == null) {
                    DnsResolver.getInstance().query(upstream, domain, DnsResolver.FLAG_NO_RETRY, dnsExecutor, signal, callback)
                } else {
                    DnsResolver.getInstance().query(upstream, domain, queryType, DnsResolver.FLAG_NO_RETRY, dnsExecutor, signal, callback)
                }
                val resolved = latch.await(DNS_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                if (!resolved) {
                    signal.cancel()
                    throw UnknownHostException("DNS lookup timeout for $domain")
                }
                failure?.let { throw it }
            } else {
                try {
                    ctx.success(upstream.getAllByName(domain).mapNotNull { it.hostAddress }.joinToString("\n"))
                } catch (_: UnknownHostException) {
                    ctx.errorCode(RCODE_NXDOMAIN)
                }
            }
        }
    }

    private class StringArray(private val values: List<String>) : StringIterator {
        private var index = 0
        override fun len(): Int = values.size
        override fun hasNext(): Boolean = index < values.size
        override fun next(): String = values.getOrElse(index++) { "" }
    }

    private class NetworkInterfaceArray(private val iterator: Iterator<BoxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): BoxNetworkInterface = iterator.next()
    }

    private fun findUpstreamNetwork(): Network? {
        networkMonitor?.currentState()?.network?.let { return it }
        return connectivity.allNetworks
            .mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                val suspended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                if (!isHandoverCandidatePhysicalNetwork(
                        hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        isSuspended = suspended,
                        isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        isBlocked = false,
                    )
                ) {
                    return@mapNotNull null
                }
                network to caps
            }
            .maxByOrNull { (network, caps) ->
                physicalNetworkPriority(
                    isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    isCurrent = network == upstreamNetwork,
                )
            }
            ?.first
    }

    private fun updateDefaultInterface(network: Network? = null) {
        val listener = interfaceListener ?: return
        val activeNet = network ?: upstreamNetwork ?: findUpstreamNetwork()
        val interfaceName = activeNet
            ?.let { connectivity.getLinkProperties(it) }
            ?.interfaceName
        val networkInterface = interfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
        if (interfaceName != null && networkInterface != null) {
            Log.i(TAG, "default interface=$interfaceName index=${networkInterface.index}")
            listener.updateDefaultInterface(interfaceName, networkInterface.index, false, false)
        } else {
            Log.w(TAG, "default interface unavailable")
            listener.updateDefaultInterface("", -1, false, false)
        }
    }

    private fun upstreamDnsServers(network: Network): List<InetAddress> =
        (
            connectivity.getLinkProperties(network)
            ?.dnsServers
            ?.filter { it.hostAddress != null }
            .orEmpty() + listOf("192.168.1.1", "1.1.1.1", "8.8.8.8").map(InetAddress::getByName)
        ).distinctBy { it.hostAddress }

    companion object {
        const val ACTION_STOP = "com.warpy.app.STOP"
        const val ACTION_QUERY_STATUS = "com.warpy.app.QUERY_STATUS"
        const val ACTION_KEEPALIVE = "com.warpy.app.KEEPALIVE"
        const val ACTION_STATUS = "com.warpy.app.STATUS"
        const val ACTION_STATS = "com.warpy.app.STATS"
        const val ACTION_SELECT_OUTBOUND = "com.warpy.app.SELECT_OUTBOUND"
        const val EXTRA_OUTBOUND_TAG = "outbound_tag"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_STABILITY_MODE = "stability_mode"
        const val EXTRA_FORCE_RESTART = "force_restart"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_CONNECTED_AT_ELAPSED = "connected_at_elapsed"
        const val EXTRA_ACTIVE_OUTBOUND_INDEX = "active_outbound_index"
        const val EXTRA_RX_SPEED = "rx_speed"
        const val EXTRA_TX_SPEED = "tx_speed"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"
        const val MESSAGE_STARTING = "Запускаем VPN"
        const val MESSAGE_RESTORING = "Восстанавливаем VPN"
        const val MESSAGE_CHECKING_PROFILE = "Проверяем профиль"
        const val MESSAGE_CONNECTED = "VPN работает"
        const val MESSAGE_STOPPED = "VPN выключен"
        const val MESSAGE_EMPTY_CONFIG = "Пустой конфиг VPN"
        fun shouldBeRunning(context: Context): Boolean =
            context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOULD_BE_RUNNING, false)

        fun activeProxyPort(): Int = publishedLocalProxyConfig?.port ?: 0

        fun activeProxyAuthorization(): String? = publishedLocalProxyConfig?.let { config ->
            val credentials = "${config.username}:${config.password}".toByteArray(Charsets.UTF_8)
            "Basic ${Base64.getEncoder().encodeToString(credentials)}"
        }

        internal fun currentSessionPublication(context: Context): VpnSessionPublication {
            val prefs = context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
            VpnSessionPublicationCodec.decode(
                prefs.getString(KEY_SESSION_PUBLICATION, null),
            )?.let { return it }

            val legacyStatus = prefs.getString(KEY_CURRENT_STATUS, STATUS_STOPPED)
                .orEmpty()
                .ifBlank { STATUS_STOPPED }
            val status = PublishedVpnStatus.entries.firstOrNull {
                it.wireValue == legacyStatus
            } ?: PublishedVpnStatus.Stopped
            val state = when (status) {
                PublishedVpnStatus.Connecting -> VpnState.Recovering
                PublishedVpnStatus.Connected -> VpnState.Connected
                PublishedVpnStatus.Stopped -> VpnState.Stopped
                PublishedVpnStatus.Error -> VpnState.Error
            }
            return VpnSessionPublication(
                generation = 0L,
                status = status,
                state = state,
                shouldRun = prefs.getBoolean(KEY_SHOULD_BE_RUNNING, false),
                connectedAtElapsedMillis = prefs.getLong(KEY_CONNECTED_AT_ELAPSED, 0L)
                    .coerceAtLeast(0L),
                runtimeProfileTag = prefs.getString(KEY_ACTIVE_OUTBOUND_TAG, null)
                    ?.takeIf(String::isNotBlank),
            )
        }

        fun isActiveOrStarting(context: Context): Boolean =
            isActiveStatus(currentSessionPublication(context).status.wireValue)

        fun isActiveStatus(status: String?): Boolean =
            status == STATUS_CONNECTED || status == STATUS_CONNECTING

        fun hasSavedConfig(context: Context): Boolean =
            context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE).let { prefs ->
                prefs.getBoolean(KEY_CONFIG_SAVED, false) ||
                    prefs.getString(KEY_LAST_CONFIG, "").orEmpty().isNotBlank()
            }

        fun statusEvents(context: Context): List<String> =
            context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STATUS_EVENTS, "")
                .orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()

        private const val CHANNEL_ID = "warpy_vpn"
        private const val NOTIFICATION_ID = 11
        private const val SERVICE_PREFS = "warpy_service"
        private const val KEY_CONFIG_SAVED = "config_saved"
        private const val KEY_LAST_CONFIG = "last_config"
        private const val KEY_SHOULD_BE_RUNNING = "should_be_running"
        private const val KEY_STABILITY_MODE = "stability_mode"
        private const val KEY_SESSION_PUBLICATION = "session_publication"
        private const val KEY_CURRENT_STATUS = "current_status"
        private const val KEY_CONNECTED_AT_ELAPSED = "connected_at_elapsed"
        private const val KEY_ACTIVE_OUTBOUND_TAG = "active_outbound_tag"
        private const val KEY_STATUS_EVENTS = "status_events"
        private const val MAX_STATUS_EVENTS = 24
        private const val TAG = "WarpyService"

        private const val PROXY_GROUP_TAG = "proxy"
        private const val DNS_TIMEOUT_MS = 4000
        private const val RCODE_NXDOMAIN = 3
        private const val KEEPALIVE_REQUEST_CODE = 1

        private const val INITIAL_PROBE_RETRIES = 8
        private const val INITIAL_PROBE_TIMEOUT_MS = 3000
        private const val WAKE_PROBE_MIN_INTERVAL_MS = 10000L
        private const val WAKE_PROBE_DELAY_MS = 1500L
        private const val WAKE_PROBE_RETRIES = 2
        private const val WAKE_PROBE_TIMEOUT_MS = 2000
        private const val RECOVERY_PROBE_RETRIES = 2
        private const val RECOVERY_PROBE_TIMEOUT_MS = 2500
        private const val HYSTERIA_RECOVERY_SETTLE_MS = 1500L
        private const val TUNNEL_WATCHDOG_INTERVAL_MS = 30_000L
        private const val TUNNEL_WATCHDOG_PROBE_RETRIES = 1
        private const val TUNNEL_WATCHDOG_PROBE_TIMEOUT_MS = 2_000
        private const val TUNNEL_WATCHDOG_FAILURES = 2

        private const val LOCAL_PROXY_BIND_ATTEMPTS = 3
        private const val STATUS_INTERVAL_NANOS = 1_000_000_000L
        @Volatile private var publishedLocalProxyConfig: LocalProxyConfig? = null

    }
}

@SuppressLint("NewApi")
private fun RoutePrefix.toIpPrefix(): IpPrefix {
    return IpPrefix(InetAddress.getByName(address()), prefix())
}

private fun PhysicalNetworkState.toIdentity(): UpstreamIdentity = UpstreamIdentity(
    networkHandle = network.networkHandle,
    interfaceName = linkProperties?.interfaceName,
    dnsServers = linkProperties?.dnsServers.orEmpty().mapNotNull(InetAddress::getHostAddress),
    isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
)

private inline fun RoutePrefixIterator.forEachRoute(action: (RoutePrefix) -> Unit) {
    while (hasNext()) {
        action(next())
    }
}

private fun StringIterator.toList(): List<String> {
    val list = mutableListOf<String>()
    while (hasNext()) {
        list.add(next())
    }
    return list
}
