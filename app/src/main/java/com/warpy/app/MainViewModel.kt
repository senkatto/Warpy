package com.warpy.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.warpy.app.data.SettingsStore
import com.warpy.app.data.SubscriptionFetcher
import com.warpy.app.data.SubscriptionParser
import com.warpy.app.data.mergeImportedProfiles
import com.warpy.app.model.AppSettings
import com.warpy.app.model.AppLanguage
import com.warpy.app.model.AppTunnelMode
import com.warpy.app.model.Diagnostics
import com.warpy.app.model.SpeedTestState
import com.warpy.app.model.VpnStatus
import com.warpy.app.model.VpnProfile
import com.warpy.app.vpn.SingBoxConfigBuilder
import com.warpy.app.vpn.VpnCommandCoordinator
import com.warpy.app.vpn.VpnLaunchResult
import com.warpy.app.updates.AndroidRelease
import com.warpy.app.updates.UpdateStage
import com.warpy.app.updates.UpdateUiState
import com.warpy.app.updates.WarpyUpdater
import android.content.Intent
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val importText: String = "",
    val diagnostics: Diagnostics = Diagnostics(),
    val commandError: String? = null,
    val pendingImportedProfileIndex: Int? = null,
    val autoConnectImportedProfileIndex: Int? = null,
    val update: UpdateUiState = UpdateUiState(),
)

internal enum class ProfileRemovalRuntimeAction {
    Keep,
    Restart,
    Stop,
}

internal fun profileRemovalRuntimeAction(
    removedIndex: Int,
    remainingProfileCount: Int,
    runtimeProfileIndex: Int?,
    activeProfileIndex: Int,
    uiConnectionActive: Boolean,
    serviceShouldRun: Boolean,
): ProfileRemovalRuntimeAction {
    if (!uiConnectionActive && !serviceShouldRun) return ProfileRemovalRuntimeAction.Keep
    if (remainingProfileCount == 0) return ProfileRemovalRuntimeAction.Stop

    val effectiveRuntimeIndex = runtimeProfileIndex
        ?: activeProfileIndex.takeUnless { serviceShouldRun }
        ?: return ProfileRemovalRuntimeAction.Restart
    return if (removedIndex <= effectiveRuntimeIndex) {
        ProfileRemovalRuntimeAction.Restart
    } else {
        ProfileRemovalRuntimeAction.Keep
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val handler = Handler(Looper.getMainLooper())
    private var pingInFlight = false
    private var cleared = false
    private var speedTestJob: kotlinx.coroutines.Job? = null
    private val _state = mutableStateOf(MainUiState(settings = store.load()))
    val state: State<MainUiState> = _state
    private val vpnCommands = VpnCommandCoordinator(application, viewModelScope)
    private val updater = WarpyUpdater(application)
    private var pendingRelease: AndroidRelease? = null

    init {
        handler.post { regenerateConfig() }
        scheduleStats()
        viewModelScope.launch {
            delay(12_000)
            while (true) {
                checkForUpdates(silent = true)
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    fun checkForUpdates(silent: Boolean = false) {
        if (_state.value.update.stage in setOf(UpdateStage.Checking, UpdateStage.Downloading)) return
        if (!silent) {
            _state.value = _state.value.copy(update = UpdateUiState(stage = UpdateStage.Checking))
        }
        viewModelScope.launch {
            runCatching { updater.check() }
                .onSuccess { release ->
                    pendingRelease = release
                    if (release != null) {
                        _state.value = _state.value.copy(
                            update = UpdateUiState(
                                stage = UpdateStage.Available,
                                version = release.version,
                            ),
                        )
                    } else if (!silent) {
                        _state.value = _state.value.copy(update = UpdateUiState())
                    }
                }
                .onFailure { error ->
                    if (!silent) {
                        _state.value = _state.value.copy(
                            update = UpdateUiState(
                                stage = UpdateStage.Error,
                                message = error.message.orEmpty(),
                            ),
                        )
                    }
                }
        }
    }

    fun downloadUpdate() {
        val release = pendingRelease ?: return
        if (_state.value.update.stage == UpdateStage.Downloading) return
        _state.value = _state.value.copy(
            update = UpdateUiState(
                stage = UpdateStage.Downloading,
                version = release.version,
                progress = 0,
            ),
        )
        viewModelScope.launch {
            runCatching {
                updater.download(release) { progress ->
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(
                            update = _state.value.update.copy(progress = progress),
                        )
                    }
                }
            }.onSuccess { apk ->
                _state.value = _state.value.copy(
                    update = UpdateUiState(
                        stage = UpdateStage.Ready,
                        version = release.version,
                        progress = 100,
                        apkPath = apk.path,
                    ),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    update = UpdateUiState(
                        stage = UpdateStage.Error,
                        version = release.version,
                        message = error.message.orEmpty(),
                    ),
                )
            }
        }
    }

    fun dismissUpdate() {
        if (_state.value.update.stage == UpdateStage.Available) {
            _state.value = _state.value.copy(update = UpdateUiState())
        }
    }

    fun canInstallUpdates(): Boolean = updater.canRequestPackageInstalls()

    fun updatePermissionIntent(): Intent = updater.installPermissionIntent()

    fun updateInstallIntent(): Intent? = _state.value.update.apkPath
        .takeIf(String::isNotBlank)
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?.let(updater::installIntent)

    fun markUpdateAwaitingPermission() {
        _state.value = _state.value.copy(
            update = _state.value.update.copy(stage = UpdateStage.AwaitingPermission),
        )
    }

    fun markUpdateInstallerOpened() {
        _state.value = _state.value.copy(
            update = _state.value.update.copy(stage = UpdateStage.Installing),
        )
    }

    fun failUpdateInstall(message: String) {
        _state.value = _state.value.copy(
            update = _state.value.update.copy(stage = UpdateStage.Error, message = message),
        )
    }

    fun setImportText(value: String) {
        _state.value = _state.value.copy(importText = value)
    }

    fun importProfile() {
        importProfile(_state.value.importText)
    }

    fun importProfile(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            fail("Подписка должна использовать HTTPS")
            return false
        }
        if (trimmed.startsWith("https://", ignoreCase = true)) {
            _state.value = _state.value.copy(
                commandError = null,
            )
            viewModelScope.launch(Dispatchers.IO) {
                val result = SubscriptionFetcher.fetch(trimmed).fold(
                    onSuccess = SubscriptionParser::parse,
                    onFailure = { Result.failure(it) },
                )
                withContext(Dispatchers.Main) {
                    result.onSuccess { parsed ->
                        finishProfileImport(parsed.profiles)
                    }.onFailure { err ->
                        fail("Ошибка загрузки подписки: ${err.message}")
                    }
                }
            }
            return true
        }

        val parsed = SubscriptionParser.parse(trimmed).getOrElse { error ->
            fail(error.message ?: "Не удалось разобрать профили")
            return false
        }
        finishProfileImport(parsed.profiles)
        return true
    }

    private fun finishProfileImport(importedProfiles: List<VpnProfile>) {
        val current = _state.value
        val merged = mergeImportedProfiles(current.settings.profiles, importedProfiles) ?: return
        val wasRunning = current.diagnostics.status == VpnStatus.Connected ||
            current.diagnostics.status == VpnStatus.Connecting ||
            com.warpy.app.vpn.WarpyService.shouldBeRunning(getApplication())
        val nextSettings = current.settings.copy(
            profiles = merged.profiles,
            activeProfileIndex = if (wasRunning) current.settings.activeProfileIndex else merged.importedIndex,
        )
        if (!updateSettings(nextSettings)) return
        _state.value = _state.value.copy(
            importText = "",
            commandError = null,
            pendingImportedProfileIndex = merged.importedIndex.takeIf { wasRunning },
            autoConnectImportedProfileIndex = merged.importedIndex.takeUnless { wasRunning },
        )
        regenerateConfig()
    }

    fun dismissImportedProfilePrompt() {
        _state.value = _state.value.copy(pendingImportedProfileIndex = null)
    }

    fun connectImportedProfile() {
        val index = _state.value.pendingImportedProfileIndex ?: return
        val settings = _state.value.settings
        if (index !in settings.profiles.indices) {
            dismissImportedProfilePrompt()
            return
        }
        if (!updateSettings(settings.copy(activeProfileIndex = index))) return
        _state.value = _state.value.copy(pendingImportedProfileIndex = null)
        regenerateConfig()
        restartActiveRuntimeNow()
    }

    fun consumeAutoConnectImportedProfile() {
        _state.value = _state.value.copy(autoConnectImportedProfileIndex = null)
    }

    fun deleteProfile(index: Int) {
        val current = _state.value
        val settings = current.settings.removeProfile(index) ?: return
        val uiConnectionActive = current.diagnostics.status == VpnStatus.Connected ||
            current.diagnostics.status == VpnStatus.Connecting
        val serviceShouldRun = com.warpy.app.vpn.WarpyService.shouldBeRunning(getApplication())
        val runtimeAction = profileRemovalRuntimeAction(
            removedIndex = index,
            remainingProfileCount = settings.profiles.size,
            runtimeProfileIndex = current.diagnostics.runtimeProfileIndex,
            activeProfileIndex = current.settings.activeProfileIndex,
            uiConnectionActive = uiConnectionActive,
            serviceShouldRun = serviceShouldRun,
        )
        if (!updateSettings(settings)) return
        _state.value = _state.value.copy(
            importText = "",
            diagnostics = _state.value.diagnostics.copy(
                runtimeProfileIndex = current.diagnostics.runtimeProfileIndex
                    .takeIf { runtimeAction == ProfileRemovalRuntimeAction.Keep },
            ),
            commandError = null,
        )
        regenerateConfig()
        when (runtimeAction) {
            ProfileRemovalRuntimeAction.Keep -> Unit
            ProfileRemovalRuntimeAction.Restart -> restartActiveRuntimeNow()
            ProfileRemovalRuntimeAction.Stop -> vpnCommands.stop()
        }
    }

    fun selectProfile(index: Int) {
        val settings = _state.value.settings
        if (index !in settings.profiles.indices) return
        if (!updateSettings(settings.copy(activeProfileIndex = index))) return

        clearCommandError()
        regenerateConfig()
        restartActiveRuntimeNow()
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        if (!updateSettings(_state.value.settings.copy(adBlockEnabled = enabled))) return
        regenerateConfig()
        scheduleActiveRuntimeRestart()
    }

    fun setBlockQuic(enabled: Boolean) {
        if (!store.markBlockQuicUserConfigured()) {
            fail("Не удалось сохранить настройки")
            return
        }
        if (!updateSettings(_state.value.settings.copy(blockQuic = enabled))) return
        regenerateConfig()
        scheduleActiveRuntimeRestart()
    }

    fun setBypassLan(enabled: Boolean) {
        if (!updateSettings(_state.value.settings.copy(bypassLan = enabled))) return
        regenerateConfig()
        scheduleActiveRuntimeRestart()
    }

    fun setStabilityModeEnabled(enabled: Boolean) {
        if (!updateSettings(_state.value.settings.copy(stabilityModeEnabled = enabled))) return
        regenerateConfig()
        scheduleActiveRuntimeRestart()
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        if (!updateSettings(_state.value.settings.copy(autoStartOnBoot = enabled))) return
        regenerateConfig()
    }

    fun setLanguage(language: AppLanguage) {
        updateSettings(_state.value.settings.copy(language = language))
    }

    fun setMtu(mtu: Int) {
        val normalizedMtu = if (mtu == 0) 0 else mtu.coerceIn(1280, 1500)
        if (!updateSettings(_state.value.settings.copy(mtu = normalizedMtu))) return
        regenerateConfig()
        scheduleActiveRuntimeRestart()
    }

    fun setAppTunnelMode(mode: AppTunnelMode) {
        if (!updateSettings(_state.value.settings.copy(appTunnelMode = mode))) return
        regenerateConfig()
        scheduleActiveRuntimeRestart()
    }

    fun toggleTunneledApp(packageName: String) {
        val settings = _state.value.settings
        val apps = settings.tunneledApps.toMutableSet()
        if (!apps.add(packageName)) apps.remove(packageName)
        if (!updateSettings(settings.copy(tunneledApps = apps))) return
        regenerateConfig()
        scheduleActiveRuntimeRestart()
    }

    fun setSiteTunnelMode(mode: AppTunnelMode) {
        if (!updateSettings(_state.value.settings.copy(siteTunnelMode = mode))) return
        regenerateConfig()
        scheduleActiveRuntimeRestart()
    }

    fun addTunneledSite(value: String) {
        val site = normalizeTunnelSite(value) ?: return
        val settings = _state.value.settings
        if (!updateSettings(settings.copy(tunneledSites = settings.tunneledSites + site))) return
        regenerateConfig()
        scheduleActiveRuntimeRestart()
    }

    fun removeTunneledSite(site: String) {
        val settings = _state.value.settings
        if (!updateSettings(settings.copy(tunneledSites = settings.tunneledSites - site))) return
        regenerateConfig()
        scheduleActiveRuntimeRestart()
    }

    internal fun startVpn(forceRestart: Boolean = false): VpnLaunchResult {
        val settings = _state.value.settings
        val validationFailure = when {
            settings.profiles.isEmpty() -> VpnLaunchResult.Failed("Сначала добавьте профиль")
            settings.appTunnelMode == AppTunnelMode.Include && settings.tunneledApps.isEmpty() ->
                VpnLaunchResult.Failed("Выберите приложения для туннелирования")
            settings.siteTunnelMode == AppTunnelMode.Include && settings.tunneledSites.isEmpty() ->
                VpnLaunchResult.Failed("Добавьте сайты для туннелирования")
            else -> null
        }
        val result = validationFailure ?: vpnCommands.start(
            settings = settings,
            config = configForCurrentSettings(),
            forceRestart = forceRestart,
        )
        handleVpnLaunchResult(result)
        return result
    }

    fun stopVpn() {
        vpnCommands.stop()
        clearCommandError()
    }

    fun applyServiceConnecting() {
        _state.value = _state.value.copy(
            diagnostics = _state.value.diagnostics.copy(
                status = VpnStatus.Connecting,
                message = "Подключение...",
            ),
            commandError = null,
        )
    }

    fun applyServiceConnected(
        message: String,
        connectedAtElapsedMillis: Long = 0L,
        runtimeProfileIndex: Int = -1,
    ) {
        val current = _state.value.diagnostics
        val connectedAt = connectedAtElapsedMillis
            .takeIf { it > 0L }
            ?: current.connectedAtMillis
                .takeIf { current.status == VpnStatus.Connected && it > 0L }
            ?: SystemClock.elapsedRealtime()
        var settingsPersistenceFailed = false
        val settings = _state.value.settings.let { currentSettings ->
            if (runtimeProfileIndex in currentSettings.profiles.indices &&
                runtimeProfileIndex != currentSettings.activeProfileIndex
            ) {
                currentSettings.copy(activeProfileIndex = runtimeProfileIndex).also { nextSettings ->
                    settingsPersistenceFailed = !store.save(nextSettings)
                }
            } else {
                currentSettings
            }
        }
        _state.value = _state.value.copy(
            settings = settings,
            diagnostics = current.copy(
                status = VpnStatus.Connected,
                message = message,
                connectedAtMillis = connectedAt,
                runtimeProfileIndex = runtimeProfileIndex.takeIf { it in settings.profiles.indices },
            ),
            commandError = if (settingsPersistenceFailed) {
                "Не удалось сохранить выбранный профиль"
            } else {
                null
            },
        )
        updateConnectionStats()
    }

    fun applyServiceStopped() {
        _state.value = _state.value.copy(
            diagnostics = _state.value.diagnostics.copy(
                status = VpnStatus.Idle,
                message = "VPN выключен",
                speedText = "—",
                pingText = "—",
                uptimeText = "",
                connectedAtMillis = 0L,
                runtimeProfileIndex = null,
            ),
            commandError = null,
        )
    }

    fun fail(message: String) {
        _state.value = _state.value.copy(
            commandError = message,
        )
    }

    fun applyServiceError(message: String) {
        _state.value = _state.value.copy(
            diagnostics = _state.value.diagnostics.copy(
                status = VpnStatus.Error,
                message = message,
                speedText = "—",
                pingText = "—",
                uptimeText = "",
                connectedAtMillis = 0L,
            ),
            commandError = null,
        )
    }

    private fun clearCommandError() {
        if (_state.value.commandError != null) {
            _state.value = _state.value.copy(commandError = null)
        }
    }

    private fun handleVpnLaunchResult(result: VpnLaunchResult) {
        when (result) {
            VpnLaunchResult.Started -> clearCommandError()
            VpnLaunchResult.PermissionRequired -> fail("Android требует разрешение на VPN")
            VpnLaunchResult.MissingConfiguration -> fail("Профиль не настроен или конфиг пустой")
            is VpnLaunchResult.Failed -> fail(result.message)
        }
    }

    private fun scheduleActiveRuntimeRestart() {
        vpnCommands.scheduleRestartIfRunning(
            settings = { _state.value.settings },
            config = ::configForCurrentSettings,
            onResult = ::handleVpnLaunchResult,
        )
    }

    private fun restartActiveRuntimeNow() {
        vpnCommands.restartNowIfRunning(
            settings = _state.value.settings,
            config = configForCurrentSettings(),
        )?.let(::handleVpnLaunchResult)
    }

    fun runSpeedTest() {
        val current = _state.value
        if (current.diagnostics.speedTest.running) return
        if (current.diagnostics.status != VpnStatus.Connected) {
            setSpeedTest(
                SpeedTestState(
                    stage = "VPN выключен",
                    errorText = "Сначала включите VPN",
                )
            )
            return
        }

        speedTestJob?.cancel()
        speedTestJob = viewModelScope.launch {
            try {
                setSpeedTest(SpeedTestState(running = true, stage = "Сейчас замеряем: пинг"))
                val clientCheck = withContext(Dispatchers.IO) { runCatching { createSpeedTestClient() } }
                val client = clientCheck.getOrNull()?.first
                val ping = clientCheck.map { it.second }
                val pingText = ping.getOrNull()?.let { "$it мс" } ?: "—"
                if (client == null) {
                    setSpeedTest(
                        SpeedTestState(
                            running = false,
                            stage = "Тест неполный",
                            pingText = pingText,
                            errorText = "Спидтест недоступен: локальный proxy не отвечает",
                        )
                    )
                    return@launch
                }

                setSpeedTest(SpeedTestState(running = true, stage = "Сейчас замеряем: загрузку", pingText = pingText))
                val download = runCatching {
                    measureDownload(client) { bytesPerSecond ->
                        updateSpeedTest {
                            val liveMbps = smoothLiveSpeed(it.liveMbps, bytesPerSecond.toMbps())
                            it.copy(
                                liveMbps = liveMbps,
                            )
                        }
                    }
                }
                val downloadText = download.getOrNull()?.let(::formatMegabits) ?: "—"

                setSpeedTest(
                    SpeedTestState(
                        running = true,
                        stage = "Сейчас замеряем: отдачу",
                        pingText = pingText,
                        downloadText = downloadText,
                        liveMbps = 0f,
                    )
                )
                val upload = runCatching {
                    measureUpload(client) { bytesPerSecond ->
                        updateSpeedTest {
                            val liveMbps = smoothLiveSpeed(it.liveMbps, bytesPerSecond.toMbps())
                            it.copy(
                                liveMbps = liveMbps,
                            )
                        }
                    }
                }
                val uploadText = upload.getOrNull()?.let(::formatMegabits) ?: "—"
                val error = speedTestError(ping.isFailure, download.isFailure, upload.isFailure)

                setSpeedTest(
                    SpeedTestState(
                        running = false,
                        stage = if (error.isBlank()) "" else "Тест неполный",
                        pingText = pingText,
                        downloadText = downloadText,
                        uploadText = uploadText,
                        liveMbps = upload.getOrNull()?.toMbps() ?: 0f,
                        errorText = error,
                    )
                )
            } finally {
                speedTestJob = null
            }
        }
    }

    fun cancelSpeedTest() {
        speedTestJob?.cancel()
        speedTestJob = null
        val current = _state.value
        if (current.diagnostics.speedTest.running) {
            setSpeedTest(
                SpeedTestState(
                    running = false,
                    stage = "Отменено",
                )
            )
        }
    }

    fun clearSpeedTestError() {
        _state.value = _state.value.copy(
            diagnostics = _state.value.diagnostics.copy(speedTest = _state.value.diagnostics.speedTest.copy(errorText = ""))
        )
    }

    fun configForCurrentSettings(): String {
        regenerateConfig()
        return _state.value.diagnostics.generatedConfig
    }

    private fun updateSettings(settings: AppSettings): Boolean {
        val normalizedSettings = settings.copy(
            activeProfileIndex = settings.activeProfileIndex.coerceIn(0, settings.profiles.lastIndex.coerceAtLeast(0))
        )
        if (!store.save(normalizedSettings)) {
            fail("Не удалось сохранить настройки")
            return false
        }
        _state.value = _state.value.copy(settings = normalizedSettings)
        return true
    }

    private fun regenerateConfig() {
        val config = runCatching { SingBoxConfigBuilder.build(_state.value.settings) }.getOrDefault("")
        _state.value = _state.value.copy(
            diagnostics = _state.value.diagnostics.copy(generatedConfig = config)
        )
    }

    private fun setSpeedTest(speedTest: SpeedTestState) {
        _state.value = _state.value.copy(
            diagnostics = _state.value.diagnostics.copy(speedTest = speedTest)
        )
    }

    private fun updateSpeedTest(transform: (SpeedTestState) -> SpeedTestState) {
        _state.value = _state.value.copy(
            diagnostics = _state.value.diagnostics.copy(
                speedTest = transform(_state.value.diagnostics.speedTest),
            )
        )
    }

    private fun scheduleStats() {
        handler.postDelayed(
            {
                updateConnectionStats()
                scheduleStats()
            },
            STATS_INTERVAL_MS,
        )
    }

    fun setTrafficStats(rxSpeed: Long, txSpeed: Long) {
        val active = _state.value.diagnostics.status == VpnStatus.Connected || _state.value.diagnostics.status == VpnStatus.Connecting
        if (!active) return
        _state.value = _state.value.copy(
            diagnostics = _state.value.diagnostics.copy(
                speedText = formatSpeed(rxSpeed.toDouble() + txSpeed.toDouble())
            )
        )
    }

    private fun updateConnectionStats() {
        val state = _state.value
        val active = state.diagnostics.status == VpnStatus.Connected || state.diagnostics.status == VpnStatus.Connecting
        if (!active) {
            if (state.diagnostics.speedText != "—" || state.diagnostics.pingText != "—" || state.diagnostics.uptimeText.isNotBlank()) {
                _state.value = state.copy(diagnostics = state.diagnostics.copy(speedText = "—", pingText = "—", uptimeText = ""))
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        val uptimeText = if (state.diagnostics.status == VpnStatus.Connected && state.diagnostics.connectedAtMillis > 0L) {
            formatUptime(now - state.diagnostics.connectedAtMillis)
        } else {
            ""
        }

        _state.value = _state.value.copy(
            diagnostics = _state.value.diagnostics.copy(
                uptimeText = uptimeText,
            )
        )

        val profile = state.settings.profile ?: return
        if (!pingInFlight && state.diagnostics.status == VpnStatus.Connected) {
            pingInFlight = true
            thread(name = "warpy-ping", isDaemon = true) {
                val ping = tcpPing(profile.server, profile.port)
                    .takeUnless { it == "—" }
                    ?: tcpPing(PING_FALLBACK_HOST, PING_FALLBACK_PORT)
                handler.post {
                    if (cleared) return@post
                    pingInFlight = false
                    _state.value = _state.value.copy(diagnostics = _state.value.diagnostics.copy(pingText = ping))
                }
            }
        }
    }

    private fun tcpPing(host: String, port: Int): String {
        val start = SystemClock.elapsedRealtime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PING_TIMEOUT_MS.toInt())
            }
            "${SystemClock.elapsedRealtime() - start} мс"
        }.getOrDefault("—")
    }

    private fun measureHttpPing(client: OkHttpClient): Long {
        var best = Long.MAX_VALUE
        repeat(3) {
            val start = SystemClock.elapsedRealtime()
            client.newCall(Request.Builder().url(PING_TEST_URL).get().build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body?.close()
            }
            best = minOf(best, SystemClock.elapsedRealtime() - start)
        }
        return best
    }

    private fun createSpeedTestClient(): Pair<OkHttpClient, Long> {
        val port = com.warpy.app.vpn.WarpyService.activeProxyPort()
        val authorization = com.warpy.app.vpn.WarpyService.activeProxyAuthorization()
        check(port > 0) { "speedtest proxy unavailable" }
        check(authorization != null) { "speedtest proxy credentials unavailable" }
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(SPEED_TEST_PROXY_HOST, port))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .proxyAuthenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", authorization)
                    .build()
            }
            .connectTimeout(SPEED_TEST_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(SPEED_TEST_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(SPEED_TEST_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
        return client to measureHttpPing(client)
    }

    private data class SpeedSample(val timestamp: Long, val bytes: Long)

    private fun getTopHalfAverage(speeds: List<Double>): Double {
        if (speeds.isEmpty()) return 0.0
        val sorted = speeds.sorted()
        val topHalf = sorted.drop(sorted.size / 2)
        return topHalf.average()
    }

    private suspend fun measureDownload(client: OkHttpClient, onProgress: (Double) -> Unit): Double = coroutineScope {
        val start = SystemClock.elapsedRealtime()
        val endAt = start + SPEED_TEST_STAGE_MS
        val totalBytes = AtomicLong(0L)
        val workers = List(DOWNLOAD_STREAMS) { index ->
            async(Dispatchers.IO) {
                runCatching { measureDownloadStream(client, index, endAt, totalBytes) }
            }
        }

        val samples = mutableListOf<SpeedSample>()
        samples.add(SpeedSample(start, 0L))
        val collectedSpeeds = mutableListOf<Double>()

        while (SystemClock.elapsedRealtime() < endAt) {
            delay(SPEED_TEST_PROGRESS_INTERVAL_MS)
            val now = SystemClock.elapsedRealtime()
            val bytes = totalBytes.get()

            samples.add(SpeedSample(now, bytes))
            while (samples.size > 1 && samples.first().timestamp < now - 1200L) {
                samples.removeAt(0)
            }

            val oldest = samples.first()
            val timeDelta = (now - oldest.timestamp).coerceAtLeast(1L)
            val bytesDelta = (bytes - oldest.bytes).coerceAtLeast(0L)
            val speedBps = bytesDelta * 1000.0 / timeDelta

            onProgress(speedBps)

            if (now - start > 1500L) {
                collectedSpeeds.add(speedBps)
            }
        }
        workers.awaitAll()

        if (collectedSpeeds.isNotEmpty()) {
            getTopHalfAverage(collectedSpeeds)
        } else {
            val bytes = totalBytes.get()
            if (bytes <= 0L) error("download failed")
            bytesPerSecond(bytes, start)
        }
    }

    private fun measureDownloadStream(
        client: OkHttpClient,
        streamIndex: Int,
        endAtMillis: Long,
        totalBytes: AtomicLong,
    ) {
        val buffer = ByteArray(64 * 1024)
        var requestIndex = 0
        while (SystemClock.elapsedRealtime() < endAtMillis) {
            var downloaded = false
            for (baseUrl in DOWNLOAD_TEST_URLS) {
                val separator = if (baseUrl.contains("?")) "&" else "?"
                val url = "$baseUrl${separator}stream=$streamIndex&run=$requestIndex&ts=${SystemClock.elapsedRealtime()}"
                runCatching {
                    client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        response.body?.byteStream()?.use { input ->
                            while (SystemClock.elapsedRealtime() < endAtMillis) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                totalBytes.addAndGet(read.toLong())
                                downloaded = true
                            }
                        }
                    }
                }
                if (downloaded || SystemClock.elapsedRealtime() >= endAtMillis) break
            }
            if (!downloaded) {
                Thread.sleep(120)
            }
            requestIndex++
        }
    }

    private suspend fun measureUpload(client: OkHttpClient, onProgress: (Double) -> Unit): Double = coroutineScope {
        val start = SystemClock.elapsedRealtime()
        val endAt = start + SPEED_TEST_STAGE_MS
        val totalBytes = AtomicLong(0L)
        val workers = List(UPLOAD_STREAMS) {
            async(Dispatchers.IO) {
                runCatching { measureUploadStream(client, endAt, totalBytes) }
            }
        }

        val samples = mutableListOf<SpeedSample>()
        samples.add(SpeedSample(start, 0L))
        val collectedSpeeds = mutableListOf<Double>()

        while (SystemClock.elapsedRealtime() < endAt) {
            delay(SPEED_TEST_PROGRESS_INTERVAL_MS)
            val now = SystemClock.elapsedRealtime()
            val bytes = totalBytes.get()

            samples.add(SpeedSample(now, bytes))
            while (samples.size > 1 && samples.first().timestamp < now - 1200L) {
                samples.removeAt(0)
            }

            val oldest = samples.first()
            val timeDelta = (now - oldest.timestamp).coerceAtLeast(1L)
            val bytesDelta = (bytes - oldest.bytes).coerceAtLeast(0L)
            val speedBps = bytesDelta * 1000.0 / timeDelta

            onProgress(speedBps)

            if (now - start > 1500L) {
                collectedSpeeds.add(speedBps)
            }
        }
        workers.awaitAll()

        if (collectedSpeeds.isNotEmpty()) {
            getTopHalfAverage(collectedSpeeds)
        } else {
            val bytes = totalBytes.get()
            if (bytes <= 0L) error("upload failed")
            bytesPerSecond(bytes, start)
        }
    }

    private fun measureUploadStream(client: OkHttpClient, endAtMillis: Long, totalBytes: AtomicLong) {
        val buffer = ByteArray(64 * 1024)
        while (SystemClock.elapsedRealtime() < endAtMillis) {
            var uploadedBytes = 0L
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = UPLOAD_BYTES_PER_REQUEST.toLong()

                override fun writeTo(sink: BufferedSink) {
                    while (uploadedBytes < UPLOAD_BYTES_PER_REQUEST) {
                        val chunk = minOf(buffer.size, UPLOAD_BYTES_PER_REQUEST - uploadedBytes.toInt())
                        sink.write(buffer, 0, chunk)
                        uploadedBytes += chunk
                    }
                }
            }
            val request = Request.Builder()
                .url(UPLOAD_TEST_URL)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                totalBytes.addAndGet(uploadedBytes)
            }
        }
    }

    private fun bytesPerSecond(bytes: Long, startMillis: Long): Double {
        val elapsed = (SystemClock.elapsedRealtime() - startMillis).coerceAtLeast(1L)
        return bytes * 1000.0 / elapsed
    }

    override fun onCleared() {
        cleared = true
        vpnCommands.close()
        speedTestJob?.cancel()
        speedTestJob = null
        handler.removeCallbacksAndMessages(null)
        super.onCleared()
    }

    private fun formatSpeed(bytesPerSecond: Double): String =
        if (bytesPerSecond >= 1024 * 1024) {
            "%.1f МБ/с".format(bytesPerSecond / 1024 / 1024)
        } else {
            "%.0f КБ/с".format(bytesPerSecond / 1024)
        }

    private fun formatMegabits(bytesPerSecond: Double): String =
        "%.0f Мбит/с".format(bytesPerSecond * 8.0 / 1_000_000.0)

    private fun speedTestError(pingFailed: Boolean, downloadFailed: Boolean, uploadFailed: Boolean): String {
        val failed = buildList {
            if (pingFailed) add("пинг")
            if (downloadFailed) add("загрузка")
            if (uploadFailed) add("отдача")
        }
        return if (failed.isEmpty()) "" else "Не удалось измерить: ${failed.joinToString(", ")}"
    }

    private fun Double.toMbps(): Float =
        (this * 8.0 / 1_000_000.0).toFloat().coerceAtLeast(0f)

    private fun smoothLiveSpeed(previous: Float, next: Float): Float =
        if (previous <= 0f) next else previous * 0.70f + next * 0.30f

    private fun formatUptime(elapsedMillis: Long): String {
        val totalSeconds = (elapsedMillis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private companion object {
        const val STATS_INTERVAL_MS = 1000L
        const val PING_TIMEOUT_MS = 2500L
        const val PING_FALLBACK_HOST = "1.1.1.1"
        const val PING_FALLBACK_PORT = 443
        const val SPEED_TEST_PROXY_HOST = "127.0.0.1"
        const val SPEED_TEST_TIMEOUT_MS = 15_000
        const val SPEED_TEST_STAGE_MS = 7_000L
        const val SPEED_TEST_PROGRESS_INTERVAL_MS = 250L
        const val DOWNLOAD_STREAMS = 6
        const val UPLOAD_STREAMS = 4
        const val UPLOAD_BYTES_PER_REQUEST = 256 * 1024
        const val PING_TEST_URL = "https://www.gstatic.com/generate_204"
        val DOWNLOAD_TEST_URLS = arrayOf(
            "https://speed.cloudflare.com/__down?bytes=50000000",
            "https://proof.ovh.net/files/100Mb.dat",
            "https://ash-speed.hetzner.com/100MB.bin",
        )
        const val UPLOAD_TEST_URL = "https://speed.cloudflare.com/__up"
    }
}

private fun normalizeTunnelSite(value: String): String? {
    val host = value.trim()
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore('/')
        .substringBefore(':')
        .trim('.')
    return host.takeIf { it.isNotBlank() && it.contains('.') && it.none(Char::isWhitespace) }
}

internal fun AppSettings.removeProfile(index: Int): AppSettings? {
    if (index !in profiles.indices) return null

    val remainingProfiles = profiles.filterIndexed { profileIndex, _ -> profileIndex != index }
    val nextActiveIndex = when {
        remainingProfiles.isEmpty() -> 0
        index < activeProfileIndex -> activeProfileIndex - 1
        index == activeProfileIndex -> index.coerceAtMost(remainingProfiles.lastIndex)
        else -> activeProfileIndex
    }
    return copy(
        profiles = remainingProfiles,
        activeProfileIndex = nextActiveIndex,
    )
}
