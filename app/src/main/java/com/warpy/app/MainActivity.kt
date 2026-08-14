package com.warpy.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.RoundedCorner
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max

import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warpy.app.model.AppSettings
import com.warpy.app.model.AppLanguage
import com.warpy.app.model.Diagnostics
import com.warpy.app.model.Protocol
import com.warpy.app.model.SpeedTestState
import com.warpy.app.model.AppTunnelMode
import com.warpy.app.model.VpnProfile
import com.warpy.app.model.VpnStatus
import com.warpy.app.data.ProfileLinkSerializer
import com.warpy.app.localization.WarpyLocalization
import com.warpy.app.localization.resolveAppLanguage
import com.warpy.app.ui.WarpyTheme
import com.warpy.app.vpn.WarpyService
import com.warpy.app.vpn.SingBoxConfigBuilder
import com.warpy.app.vpn.VpnPermissionState
import com.warpy.app.vpn.VpnStartHelper
import com.warpy.app.vpn.WarpyTileService
import com.warpy.app.updates.UpdateStage
import com.warpy.app.updates.UpdateUiState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

private const val SPEED_TEST_GAUGE_MAX_MBPS = 300f
private val SETTINGS_TITLE_GREEN = Color(0xFF55DCA7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TileService.requestListeningState(this, ComponentName(this, WarpyTileService::class.java))
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            WarpyTheme {
                WarpyApp()
            }
        }
    }
}

private val LocalWarpyLanguage = staticCompositionLocalOf { AppLanguage.English }

@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    MaterialText(
        text = WarpyLocalization.text(text, LocalWarpyLanguage.current),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}

@Composable
private fun localizedText(source: String): String =
    WarpyLocalization.text(source, LocalWarpyLanguage.current)

@Composable
private fun WarpyApp(viewModel: MainViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state
    val systemLanguage = LocalConfiguration.current.locales[0].language
    val effectiveLanguage = remember(state.settings.language, systemLanguage) {
        resolveAppLanguage(state.settings.language, systemLanguage)
    }
    var showSettings by remember { mutableStateOf(false) }
    var showAppTunneling by remember { mutableStateOf(false) }
    var showStatusPage by remember { mutableStateOf(false) }
    var showSpeedTest by remember { mutableStateOf(false) }
    var showAddProfile by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var shareProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var deleteProfileIndex by remember { mutableStateOf<Int?>(null) }
    var connectionAttemptId by remember { mutableStateOf(0) }
    var borderGlowArmed by remember { mutableStateOf(false) }
    var borderGlowEventId by remember { mutableStateOf(0) }
    var consumedBorderGlowEventId by remember { mutableStateOf(0) }
    val displayedProfileIndex = state.diagnostics.runtimeProfileIndex
        ?.takeIf { state.diagnostics.status == VpnStatus.Connected }
        ?: state.settings.activeProfileIndex
    val displayedProfile = state.settings.profiles.getOrNull(displayedProfileIndex)
    val hasSecondarySurface = deleteProfileIndex != null ||
        shareProfile != null ||
        showQrScanner ||
        showAddProfile ||
        showProfiles ||
        showAppTunneling ||
        showStatusPage ||
        showSpeedTest ||
        showSettings

    LaunchedEffect(state.diagnostics.status, hasSecondarySurface) {
        when (state.diagnostics.status) {
            VpnStatus.Connecting -> borderGlowArmed = !hasSecondarySurface
            VpnStatus.Connected -> {
                if (borderGlowArmed && !hasSecondarySurface) {
                    borderGlowEventId += 1
                }
                borderGlowArmed = false
            }
            VpnStatus.Idle,
            VpnStatus.Error -> borderGlowArmed = false
        }
    }

    BackHandler(enabled = hasSecondarySurface) {
        when {
            deleteProfileIndex != null -> deleteProfileIndex = null
            shareProfile != null -> shareProfile = null
            showQrScanner -> showQrScanner = false
            showAddProfile -> showAddProfile = false
            showProfiles -> showProfiles = false
            showAppTunneling -> showAppTunneling = false
            showStatusPage -> showStatusPage = false
            showSpeedTest -> {
                viewModel.cancelSpeedTest()
                showSpeedTest = false
            }
            showSettings -> showSettings = false
        }
    }

    DisposableEffect(context, lifecycleOwner) {
        val mainHandler = Handler(Looper.getMainLooper())
        var active = true
        val deferredConfigBuild = Runnable {
            if (active) viewModel.configForCurrentSettings()
        }
        val deferredStatusQuery = Runnable {
            if (active) {
                syncViewModelWithSavedServiceStatus(context, viewModel)
                queryVpnServiceStatus(context)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WarpyService.ACTION_STATUS -> {
                        applyServiceStatus(
                            viewModel = viewModel,
                            status = intent.getStringExtra(WarpyService.EXTRA_STATUS),
                            message = intent.getStringExtra(WarpyService.EXTRA_MESSAGE),
                            connectedAtElapsedMillis = intent.getLongExtra(WarpyService.EXTRA_CONNECTED_AT_ELAPSED, 0L),
                            runtimeProfileIndex = intent.getIntExtra(WarpyService.EXTRA_ACTIVE_OUTBOUND_INDEX, -1),
                            includeError = true,
                        )
                    }
                    WarpyService.ACTION_STATS -> {
                        val rxSpeed = intent.getLongExtra(WarpyService.EXTRA_RX_SPEED, 0L)
                        val txSpeed = intent.getLongExtra(WarpyService.EXTRA_TX_SPEED, 0L)
                        viewModel.setTrafficStats(rxSpeed, txSpeed)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WarpyService.ACTION_STATUS)
            addAction(WarpyService.ACTION_STATS)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainHandler.post(deferredStatusQuery)
                viewModel.checkForUpdates(silent = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mainHandler.post(deferredConfigBuild)
        mainHandler.post(deferredStatusQuery)

        onDispose {
            active = false
            mainHandler.removeCallbacks(deferredConfigBuild)
            mainHandler.removeCallbacks(deferredStatusQuery)
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            connectionAttemptId += 1
            viewModel.startVpn()
        } else {
            viewModel.fail("Android не выдал разрешение на VPN")
        }
    }

    val requestVpnStart: () -> Unit = {
        when (val permission = VpnStartHelper.checkPermission(context)) {
            VpnPermissionState.Granted -> {
                connectionAttemptId += 1
                viewModel.startVpn()
            }
            is VpnPermissionState.Required -> {
                runCatching { vpnPermissionLauncher.launch(permission.intent) }
                    .onFailure { viewModel.fail("Android не открыл запрос разрешения VPN") }
            }
            is VpnPermissionState.Failed -> viewModel.fail(permission.message)
        }
    }

    val updatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (viewModel.canInstallUpdates()) {
            val installIntent = viewModel.updateInstallIntent()
            if (installIntent != null) {
                runCatching { context.startActivity(installIntent) }
                    .onSuccess { viewModel.markUpdateInstallerOpened() }
                    .onFailure { viewModel.failUpdateInstall(it.message.orEmpty()) }
            } else {
                viewModel.failUpdateInstall("Файл обновления недоступен")
            }
        } else {
            viewModel.failUpdateInstall("Разрешите Warpy устанавливать обновления")
        }
    }

    LaunchedEffect(state.update.stage, state.update.apkPath) {
        if (state.update.stage != UpdateStage.Ready) return@LaunchedEffect
        val installIntent = viewModel.updateInstallIntent()
        if (installIntent == null) {
            viewModel.failUpdateInstall("Файл обновления недоступен")
        } else if (viewModel.canInstallUpdates()) {
            runCatching { context.startActivity(installIntent) }
                .onSuccess { viewModel.markUpdateInstallerOpened() }
                .onFailure { viewModel.failUpdateInstall(it.message.orEmpty()) }
        } else {
            viewModel.markUpdateAwaitingPermission()
            updatePermissionLauncher.launch(viewModel.updatePermissionIntent())
        }
    }

    LaunchedEffect(state.autoConnectImportedProfileIndex) {
        if (state.autoConnectImportedProfileIndex != null) {
            viewModel.consumeAutoConnectImportedProfile()
            requestVpnStart()
        }
    }

    CompositionLocalProvider(LocalWarpyLanguage provides effectiveLanguage) {
    Box(modifier = Modifier.fillMaxSize()) {
    if (showStatusPage) {
        StatusPage(
            settings = state.settings,
            diagnostics = state.diagnostics,
            onBack = { showStatusPage = false },
        )
    } else if (showAppTunneling) {
        AppTunnelingPage(
            mode = state.settings.appTunnelMode,
            selectedApps = state.settings.tunneledApps,
            siteMode = state.settings.siteTunnelMode,
            selectedSites = state.settings.tunneledSites,
            onMode = { mode ->
                viewModel.setAppTunnelMode(mode)
            },
            onToggleApp = { packageName ->
                viewModel.toggleTunneledApp(packageName)
            },
            onSiteMode = viewModel::setSiteTunnelMode,
            onAddSite = viewModel::addTunneledSite,
            onRemoveSite = viewModel::removeTunneledSite,
            onBack = { showAppTunneling = false },
        )
    } else if (showSettings) {
        SettingsPage(
            adBlockEnabled = state.settings.adBlockEnabled,
            blockQuic = state.settings.blockQuic,
            bypassLan = state.settings.bypassLan,
            stabilityModeEnabled = state.settings.stabilityModeEnabled,
            autoStartOnBoot = state.settings.autoStartOnBoot,
            language = state.settings.language,
            resolvedLanguage = effectiveLanguage,
            updateState = state.update,
            mtu = state.settings.mtu,
            onAdBlock = viewModel::setAdBlockEnabled,
            onBlockQuic = viewModel::setBlockQuic,
            onBypassLan = viewModel::setBypassLan,
            onStabilityMode = viewModel::setStabilityModeEnabled,
            onAutoStartOnBoot = viewModel::setAutoStartOnBoot,
            onLanguage = viewModel::setLanguage,
            onMtu = { mtu ->
                viewModel.setMtu(mtu)
            },
            onOpenStatus = { showStatusPage = true },
            onOpenAppTunneling = { showAppTunneling = true },
            onCheckUpdates = { viewModel.checkForUpdates(silent = false) },
            onBack = { showSettings = false },
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Scaffold { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    TopActions(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        onSettings = { showSettings = true },
                        onSpeedTest = {
                            showSpeedTest = true
                            viewModel.runSpeedTest()
                        },
                        onAddProfile = { showAddProfile = true },
                    )

                    ConnectButton(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 96.dp, bottom = 100.dp),
                        status = state.diagnostics.status,
                        profile = displayedProfile,
                        pingText = state.diagnostics.pingText,
                        speedText = state.diagnostics.speedText,
                        uptimeText = state.diagnostics.uptimeText,
                        message = state.diagnostics.message,
                        commandError = state.commandError,
                        connectionAttemptId = connectionAttemptId,
                        onToggle = {
                            val vpnIsRunning = state.diagnostics.status == VpnStatus.Connected ||
                                state.diagnostics.status == VpnStatus.Connecting ||
                                WarpyService.shouldBeRunning(context)
                            if (vpnIsRunning) {
                                viewModel.stopVpn()
                            } else {
                                if (state.settings.profile == null) {
                                    showAddProfile = true
                                    viewModel.fail("Добавьте профиль")
                                    return@ConnectButton
                                }
                                requestVpnStart()
                            }
                        },
                    )

                    ProfileSummary(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        profile = displayedProfile,
                        onClick = { showProfiles = true },
                    )
                }
            }

            ConnectedBorderGlow(
                connected = state.diagnostics.status == VpnStatus.Connected,
                eventId = borderGlowEventId,
                consumedEventId = consumedBorderGlowEventId,
                onEventConsumed = { consumedBorderGlowEventId = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showProfiles) {
        ProfilesOverlay(
            profiles = state.settings.profiles,
            activeIndex = displayedProfileIndex,
            onDismiss = { showProfiles = false },
            onSelect = onSelect@{ index ->
                if (index == displayedProfileIndex) {
                    showProfiles = false
                    return@onSelect
                }
                viewModel.selectProfile(index)
                connectionAttemptId += 1
                showProfiles = false
            },
            onShare = { profile -> shareProfile = profile },
            onDelete = { index -> deleteProfileIndex = index },
        )
    }

    deleteProfileIndex?.let { index ->
        state.settings.profiles.getOrNull(index)?.let { profile ->
            AlertDialog(
                onDismissRequest = { deleteProfileIndex = null },
                title = { Text("Удалить профиль?") },
                text = { Text("Профиль «${profile.displayName()}» будет удалён.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteProfile(index)
                            deleteProfileIndex = null
                            if (state.settings.profiles.size == 1) {
                                showProfiles = false
                            }
                        },
                    ) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteProfileIndex = null }) {
                        Text("Отмена")
                    }
                },
            )
        }
    }

    state.pendingImportedProfileIndex?.let { index ->
        state.settings.profiles.getOrNull(index)?.let { profile ->
            AlertDialog(
                onDismissRequest = viewModel::dismissImportedProfilePrompt,
                title = { Text("Подключиться к новому профилю?") },
                text = { Text(profile.displayName()) },
                confirmButton = {
                    TextButton(onClick = viewModel::connectImportedProfile) {
                        Text("Подключиться")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissImportedProfilePrompt) {
                        Text("Оставить текущий")
                    }
                },
            )
        }
    }

    shareProfile?.let { profile ->
        ShareProfileDialog(
            profile = profile,
            onDismiss = { shareProfile = null },
        )
    }

    if (showAddProfile) {
        AddProfileDialog(
            onImportValue = viewModel::importProfile,
            onScanQr = {
                showAddProfile = false
                showQrScanner = true
            },
            onDismiss = { showAddProfile = false },
        )
    }

    if (showQrScanner) {
        QrScannerScreen(
            onResult = { value ->
                viewModel.importProfile(value).also { imported ->
                    if (imported) showQrScanner = false
                }
            },
            onBack = { showQrScanner = false },
        )
    }

    if (showSpeedTest) {
        SpeedTestDialog(
            state = state.diagnostics.speedTest,
            onRun = viewModel::runSpeedTest,
            onDismiss = {
                viewModel.cancelSpeedTest()
                viewModel.clearSpeedTestError()
                showSpeedTest = false
            },
        )
    }

    UpdateBanner(
        state = state.update,
        onLater = viewModel::dismissUpdate,
        onInstall = viewModel::downloadUpdate,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
    }
    }
}

@Composable
private fun UpdateBanner(
    state: UpdateUiState,
    onLater: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.stage in setOf(
        UpdateStage.Available,
        UpdateStage.Downloading,
        UpdateStage.AwaitingPermission,
        UpdateStage.Error,
    )
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        Surface(
            color = Color(0xFF202123),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0x3300C07F)),
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (state.stage) {
                                UpdateStage.Downloading -> "Скачиваем обновление"
                                UpdateStage.AwaitingPermission -> "Разрешите установку обновления"
                                UpdateStage.Error -> "Не удалось установить обновление"
                                else -> "${localizedText("Доступна версия")} ${state.version}"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.stage == UpdateStage.Available) {
                            Text(
                                text = "Warpy обновится и снова откроется",
                                color = Color.White.copy(alpha = 0.54f),
                                fontSize = 11.sp,
                            )
                        }
                    }
                    if (state.stage == UpdateStage.Available || state.stage == UpdateStage.Error) {
                        TextButton(onClick = onLater) { Text("Позже") }
                        TextButton(onClick = onInstall) {
                            Text(if (state.stage == UpdateStage.Error) "Повторить" else "Обновить")
                        }
                    }
                }
                if (state.stage == UpdateStage.Downloading) {
                    LinearProgressIndicator(
                        progress = { (state.progress ?: 0) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF00C07F),
                        trackColor = Color.White.copy(alpha = 0.08f),
                    )
                    Text(
                        text = state.progress?.let { "$it%" } ?: "Загрузка...",
                        color = Color.White.copy(alpha = 0.54f),
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

private fun syncViewModelWithSavedServiceStatus(context: Context, viewModel: MainViewModel) {
    val publication = WarpyService.currentSessionPublication(context)
    applyServiceStatus(
        viewModel = viewModel,
        status = publication.status.wireValue,
        message = null,
        connectedAtElapsedMillis = publication.connectedAtElapsedMillis,
        runtimeProfileIndex = publication.activeOutboundIndex,
        includeError = false,
    )
}

private fun queryVpnServiceStatus(context: Context) {
    context.startService(Intent(context, WarpyService::class.java).setAction(WarpyService.ACTION_QUERY_STATUS))
}

private fun applyServiceStatus(
    viewModel: MainViewModel,
    status: String?,
    message: String?,
    connectedAtElapsedMillis: Long,
    runtimeProfileIndex: Int,
    includeError: Boolean,
) {
    when (status) {
        WarpyService.STATUS_CONNECTING -> viewModel.applyServiceConnecting()
        WarpyService.STATUS_CONNECTED -> viewModel.applyServiceConnected(
            message = "VPN работает",
            connectedAtElapsedMillis = connectedAtElapsedMillis,
            runtimeProfileIndex = runtimeProfileIndex,
        )
        WarpyService.STATUS_STOPPED -> viewModel.applyServiceStopped()
        WarpyService.STATUS_ERROR -> if (includeError) {
            viewModel.applyServiceError(message ?: "VPN не запустился")
        }
    }
}

@Composable
private fun TopActions(
    modifier: Modifier = Modifier,
    onSettings: () -> Unit,
    onSpeedTest: () -> Unit,
    onAddProfile: () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Image(
            painter = painterResource(R.drawable.wapry_logo),
            contentDescription = null,
            modifier = Modifier
                .width(109.dp)
                .height(20.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundIconButton(Icons.Default.Add, "Добавить профиль", onAddProfile)
            RoundIconButton(Icons.Default.Speed, "Замерить скорость", onSpeedTest)
            RoundIconButton(Icons.Default.Settings, "Настройки", onSettings)
        }
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = localizedText(contentDescription), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun SpeedTestDialog(
    state: SpeedTestState,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(start = 22.dp, top = 12.dp, end = 22.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Spacer(modifier = Modifier.height(8.dp))
                SpeedTestResultArea(
                    state = state,
                )
                if (state.errorText.isNotBlank()) {
                    Text(
                        text = state.errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Закрыть")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = onRun,
                        enabled = !state.running,
                    ) {
                        Text(if (state.running) "Идет тест" else "Запустить")
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedTestResultArea(state: SpeedTestState) {
    val finalReady = !state.running &&
        state.downloadText != "—" &&
        state.uploadText != "—" &&
        state.pingText != "—"
    val gaugeAlpha by animateFloatAsState(
        targetValue = if (finalReady) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "speed-test-gauge-alpha",
    )
    val resultAlpha by animateFloatAsState(
        targetValue = if (finalReady) 1f else 0f,
        animationSpec = tween(durationMillis = 220, delayMillis = 90),
        label = "speed-test-result-alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(214.dp),
        contentAlignment = Alignment.Center,
    ) {
        SpeedTestGauge(
            valueMbps = state.liveMbps,
            running = state.running,
            uploadMode = state.stage.contains("отда", ignoreCase = true) ||
                (!state.running && state.uploadText != "—"),
            modifier = Modifier.alpha(gaugeAlpha),
        )
        SpeedTestFinalResult(
            pingText = state.pingText,
            downloadText = state.downloadText,
            uploadText = state.uploadText,
            modifier = Modifier.alpha(resultAlpha),
        )
    }
}

@Composable
private fun SpeedTestFinalResult(
    pingText: String,
    downloadText: String,
    uploadText: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SpeedTestFinalRow(
            arrowDown = true,
            value = downloadText,
            color = Color(0xFF00C07F),
        )
        Spacer(modifier = Modifier.height(10.dp))
        SpeedTestFinalRow(
            arrowDown = false,
            value = uploadText,
            color = Color(0xFF4DA3FF),
        )
        Spacer(modifier = Modifier.height(10.dp))
        SpeedTestFinalRow(
            arrowDown = null,
            value = pingText,
            color = Color(0xFFFFA726),
        )
    }
}

@Composable
private fun SpeedTestFinalRow(arrowDown: Boolean?, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (arrowDown == null) {
                ThinPingIcon(modifier = Modifier.size(20.dp))
            } else {
                ThinTransferArrow(
                    down = arrowDown,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(9.dp))
        val parts = splitSpeedText(value)
        Text(
            text = parts.first,
            modifier = Modifier.alignByBaseline(),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Black,
                fontFeatureSettings = "tnum",
            ),
            color = color,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = parts.second,
            modifier = Modifier.alignByBaseline(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun ThinTransferArrow(down: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.55.dp.toPx()
        val shaftTop = size.height * 0.18f
        val shaftBottom = size.height * 0.76f
        val centerX = size.width * 0.5f
        val arrowHalf = size.width * 0.22f
        val arrowY = shaftBottom
        val drawArrow = {
            drawLine(
                color = Color.White,
                start = Offset(centerX, shaftTop),
                end = Offset(centerX, shaftBottom),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX, arrowY),
                end = Offset(centerX - arrowHalf, arrowY - arrowHalf),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX, arrowY),
                end = Offset(centerX + arrowHalf, arrowY - arrowHalf),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        if (down) {
            drawArrow()
        } else {
            rotate(180f, pivot = center) {
                drawArrow()
            }
        }
    }
}

@Composable
private fun ThinPingIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.55.dp.toPx()
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        drawCircle(
            color = Color.White,
            radius = min(size.width, size.height) * 0.17f,
            center = center,
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = Color.White,
            radius = min(size.width, size.height) * 0.36f,
            center = center,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

private fun splitSpeedText(value: String): Pair<String, String> {
    val firstSpace = value.indexOf(' ')
    return if (firstSpace > 0) {
        value.substring(0, firstSpace) to value.substring(firstSpace + 1)
    } else {
        value to ""
    }
}

@Composable
private fun SpeedTestGauge(
    valueMbps: Float,
    running: Boolean,
    uploadMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val animatedValue by animateFloatAsState(
        targetValue = valueMbps.coerceAtLeast(0f),
        animationSpec = tween(durationMillis = 360),
        label = "speed-test-gauge",
    )
    val overLimit = animatedValue > SPEED_TEST_GAUGE_MAX_MBPS
    var shakePhase by remember { mutableStateOf(0f) }
    LaunchedEffect(overLimit, running) {
        if (!overLimit || !running) {
            shakePhase = 0f
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (overLimit && running) {
            shakePhase = (withFrameNanos { it } - start) / 1_000_000_000f
        }
    }
    val modeText = if (uploadMode) "▲ ▲ ▲" else "▼ ▼ ▼"
    val modeColor = if (uploadMode) Color(0xFF4DA3FF) else Color(0xFF00C07F)
    val shakeOffset = if (overLimit && running) {
        (sin(shakePhase * 54f) * 2.2f).dp
    } else {
        0.dp
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(208.dp)
            .offset(x = shakeOffset),
        contentAlignment = Alignment.Center,
    ) {
        WarpStarfield(
            running = running,
            uploadMode = uploadMode,
            valueMbps = valueMbps,
            modifier = Modifier.offset(y = (-20).dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-20).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = modeText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                ),
                color = modeColor,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "%.0f".format(animatedValue),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = "Мбит/с",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ConnectButton(
    modifier: Modifier = Modifier,
    status: VpnStatus,
    profile: VpnProfile?,
    pingText: String,
    speedText: String,
    uptimeText: String,
    message: String,
    commandError: String?,
    connectionAttemptId: Int,
    onToggle: () -> Unit,
) {
    val connected = status == VpnStatus.Connected
    val connecting = status == VpnStatus.Connecting
    val running = connected || connecting
    val toggleLabel = localizedText(if (running) "Выключить VPN" else "Включить VPN")
    val error = status == VpnStatus.Error || commandError != null
    val errorMessage = commandError ?: message
    val activeGreen = Color(0xFF00C07F)
    val iconTint = if (connected) activeGreen else MaterialTheme.colorScheme.onSurface
    val startHoleDurationSeconds = 0.36f
    val stopFillDurationSeconds = 0.34f
    val circleColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val screenColor = MaterialTheme.colorScheme.background
    val particleFieldSize = 286.dp
    val connectionCoreSize = 206.dp
    var startElapsedSeconds by remember { mutableStateOf(0f) }
    var circleFillProgress by remember { mutableStateOf(if (connected) 0f else 1f) }
    var showConnectedConfirmation by remember(connectionAttemptId) { mutableStateOf(false) }
    LaunchedEffect(connecting, connected) {
        startElapsedSeconds = 0f
        if (connecting && !connected) {
            val start = withFrameNanos { it }
            while (connecting && !connected && startElapsedSeconds < startHoleDurationSeconds) {
                startElapsedSeconds = (withFrameNanos { it } - start) / 1_000_000_000f
            }
        }
    }
    LaunchedEffect(connected, connecting) {
        if (connected) {
            circleFillProgress = 0f
        } else if (!connecting && circleFillProgress < 1f) {
            val startProgress = circleFillProgress
            val start = withFrameNanos { it }
            while (!connected && !connecting && circleFillProgress < 1f) {
                val elapsed = (withFrameNanos { it } - start) / 1_000_000_000f
                val progress = (elapsed / stopFillDurationSeconds).coerceIn(0f, 1f)
                val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
                circleFillProgress = startProgress + (1f - startProgress) * eased
            }
        } else if (!connecting) {
            circleFillProgress = 1f
        }
    }
    LaunchedEffect(connected, connectionAttemptId) {
        showConnectedConfirmation = connected && connectionAttemptId > 0
        if (showConnectedConfirmation) {
            delay(1_250L)
            showConnectedConfirmation = false
        }
    }
    val showParticles = connected || (connecting && startElapsedSeconds >= startHoleDurationSeconds)
    val visibleCircleFillProgress = when {
        connected -> 0f
        connecting && startElapsedSeconds >= startHoleDurationSeconds -> 0f
        else -> circleFillProgress
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
    ) {
        val detailMinimumHeight = if (connected) 150.dp else 96.dp
        val circleTop = minOf(
            100.dp,
            ((maxHeight - particleFieldSize - detailMinimumHeight) / 2f).coerceAtLeast(0.dp),
        )
        val detailsTop = circleTop + particleFieldSize
        val detailsHeight = (maxHeight - detailsTop).coerceAtLeast(0.dp)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = circleTop)
                .size(particleFieldSize),
        ) {
            ConnectionCircleFill(
                modifier = Modifier.size(connectionCoreSize),
                progress = visibleCircleFillProgress,
                circleColor = circleColor,
                screenColor = screenColor,
            )
            Surface(
                modifier = Modifier
                    .size(connectionCoreSize)
                    .clip(CircleShape)
                    .semantics {
                        contentDescription = toggleLabel
                    }
                    .clickable(
                        onClick = onToggle,
                        onClickLabel = toggleLabel,
                    ),
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = iconTint,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Empty by design: particles sit above the circle, icon is the top layer.
                }
            }
            if (connecting && !connected) {
                ConnectionStartHole(
                    modifier = Modifier.size(connectionCoreSize),
                    progress = (startElapsedSeconds / startHoleDurationSeconds).coerceIn(0f, 1f),
                    color = screenColor,
                )
            }
            ConnectParticleField(
                modifier = Modifier.size(particleFieldSize),
                connecting = connecting,
                connected = connected,
                visible = showParticles,
                connectionAttemptId = connectionAttemptId,
            )
            Box(
                modifier = Modifier.size(connectionCoreSize),
                contentAlignment = Alignment.Center,
            ) {
                if (!connected) {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = iconTint,
                    )
                } else if (connected && showConnectedConfirmation) {
                    Text(
                        text = "ПОДКЛЮЧЕНО",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = iconTint,
                        maxLines = 1,
                    )
                } else if (connected && uptimeText.isNotBlank()) {
                    Text(
                        text = uptimeText,
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = iconTint,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = detailsTop)
                .fillMaxWidth()
                .height(detailsHeight),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.offset(y = (-12).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = when {
                        connected -> profile?.displayName().orEmpty()
                        connecting -> "Устанавливаем туннель"
                        profile == null -> "Добавьте профиль кнопкой +"
                        else -> profile.displayName()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (profile != null) {
                    ProtocolChip(profile.protocol)
                }
                if (error && errorMessage.isNotBlank()) {
                    val userFriendlyMessage = when {
                        errorMessage.contains("fatal", ignoreCase = true) || errorMessage.contains("panic", ignoreCase = true) -> "Ошибка запуска ядра VPN"
                        errorMessage.contains("port", ignoreCase = true) -> "Ошибка: Порт занят другим приложением"
                        errorMessage.contains("permission", ignoreCase = true) -> "Ошибка доступа к VPN интерфейсу"
                        errorMessage.contains("config", ignoreCase = true) -> "Ошибка конфигурации профиля"
                        errorMessage.contains("timeout", ignoreCase = true) -> "Превышено время ожидания сервера"
                        errorMessage.contains("refused", ignoreCase = true) -> "Соединение отклонено сервером"
                        errorMessage.contains("ТУН", ignoreCase = true) || errorMessage.contains("туннель", ignoreCase = true) -> errorMessage
                        else -> "Ошибка подключения к VPN"
                    }
                    Text(
                        text = userFriendlyMessage,
                        modifier = Modifier.widthIn(max = 360.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (connected) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MetricText(speedText, fallbackUnit = "КБ/с")
                        MetricText(pingText, fallbackUnit = "мс")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCircleFill(
    modifier: Modifier = Modifier,
    progress: Float,
    circleColor: Color,
    screenColor: Color,
) {
    Canvas(modifier = modifier) {
        if (progress <= 0f) return@Canvas
        val radius = min(size.width, size.height) * 0.5f
        drawCircle(
            color = circleColor,
            radius = radius,
            center = center,
        )
        if (progress < 1f) {
            drawCircle(
                color = screenColor,
                radius = radius * (1f - progress),
                center = center,
            )
        }
    }
}

@Composable
private fun ConnectionStartHole(modifier: Modifier = Modifier, progress: Float, color: Color) {
    Canvas(modifier = modifier) {
        val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        drawCircle(
            color = color,
            radius = min(size.width, size.height) * 0.58f * eased,
            center = center,
        )
    }
}

@Composable
private fun ConnectParticleField(
    modifier: Modifier = Modifier,
    connecting: Boolean,
    connected: Boolean,
    visible: Boolean,
    connectionAttemptId: Int,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var elapsedSeconds by remember { mutableStateOf(0f) }
    var pullStartedAt by remember { mutableStateOf<Float?>(null) }
    var particlesRevealed by remember { mutableStateOf(connected) }
    var lifecycleActive by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val colorProgress = remember { Animatable(if (connected) 1f else 0f) }
    val particleConnecting = connecting
    val particleConnected = connected
    val animationActive = particlesRevealed &&
        (particleConnecting || particleConnected) &&
        lifecycleActive
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleActive = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(connectionAttemptId) {
        if (connectionAttemptId == 0) return@LaunchedEffect
        particlesRevealed = false
        elapsedSeconds = 0f
        pullStartedAt = null
        colorProgress.snapTo(0f)
    }
    LaunchedEffect(visible, connectionAttemptId) {
        if (visible) particlesRevealed = true
    }
    LaunchedEffect(connecting, connected) {
        if (!connecting && !connected) {
            particlesRevealed = false
        }
    }
    LaunchedEffect(animationActive) {
        if (!animationActive) return@LaunchedEffect
        val start = withFrameNanos { it } - (elapsedSeconds * 1_000_000_000L).toLong()
        while (true) {
            elapsedSeconds = (withFrameNanos { it } - start) / 1_000_000_000f
        }
    }
    LaunchedEffect(particleConnecting, particleConnected, connectionAttemptId) {
        when {
            particleConnecting -> {
                elapsedSeconds = 0f
                pullStartedAt = null
                colorProgress.snapTo(0f)
            }
            particleConnected -> {
                pullStartedAt = null
                delay(180L)
                colorProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 760),
                )
                pullStartedAt = elapsedSeconds
            }
            else -> {
                pullStartedAt = null
                colorProgress.snapTo(0f)
            }
        }
    }
    val connectedPalette = listOf(Color(0xFF00C07F), Color(0xFF00C07F), Color(0xFF00C07F))
    val connectingPalette = listOf(
        MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val particles = remember {
        List(86) { index ->
            ParticleSeed(
                angle = pseudoRandom(index * index + 19, 11) * 360f,
                introDelay = pseudoRandom(index * 17 + 5, 41) * 0.42f,
                cycleSeconds = 1.85f + pseudoRandom(index * 13 + 7, 29) * 1.15f,
                pullDelay = pseudoRandom(index * 31 + 9, 47) * 0.42f,
                radius = 1.6f + (index % 4) * 0.4f,
                edgeOffset = -35f + pseudoRandom(index, 37) * 70f,
                endRadius = 15f + pseudoRandom(index, 51) * 12f,
                wobbleDistance = 0.8f + pseudoRandom(index, 83) * 3.5f,
                phase = pseudoRandom(index, 89) * 360f,
                secondPhase = pseudoRandom(index, 103) * 360f,
                colorDelay = pseudoRandom(index * 23 + 3, 71) * 0.18f,
                colorIndex = index % 3,
            )
        }
    }

    Canvas(modifier = modifier) {
        if (!animationActive) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val edgeRadius = min(size.width, size.height) * 0.34f
        val motionScale = density.coerceIn(1f, 3f)
        particles.forEach { particle ->
            val edgeTime = (elapsedSeconds - particle.introDelay).coerceAtLeast(0f)
            val introAlpha = (edgeTime / 0.2f).coerceIn(0f, 1f)
            val pullElapsed = pullStartedAt?.let { startedAt ->
                (elapsedSeconds - startedAt - particle.pullDelay).coerceAtLeast(0f)
            } ?: 0f
            val pulling = pullStartedAt != null && pullElapsed > 0f
            val local = if (pulling) (pullElapsed / particle.cycleSeconds) % 1f else 0f
            val firstPullCycle = pulling && pullElapsed < particle.cycleSeconds
            val fadeIn = if (!pulling || firstPullCycle) 1f else (local / 0.1f).coerceIn(0f, 1f)
            val fadeOut = if (pulling) ((1f - local) / 0.22f).coerceIn(0f, 1f) else 1f
            val blink = 0.78f + 0.22f * sin(Math.toRadians((particle.phase + elapsedSeconds * 80f).toDouble())).toFloat()
            val particleColorProgress =
                ((colorProgress.value - particle.colorDelay) / (1f - particle.colorDelay))
                    .coerceIn(0f, 1f)
            val particleColor = lerp(
                connectingPalette[particle.colorIndex],
                connectedPalette[particle.colorIndex],
                particleColorProgress,
            )
            val activeAlpha = 0.64f + (0.82f - 0.64f) * particleColorProgress
            val angle = Math.toRadians(particle.angle.toDouble())
            val radial = Offset(
                x = cos(angle).toFloat(),
                y = sin(angle).toFloat(),
            )
            val tangent = Offset(-radial.y, radial.x)
            val startRadius = edgeRadius + particle.edgeOffset
            val pullStart = 0.34f
            val pull = if (pulling) ((local - pullStart) / (1f - pullStart)).coerceIn(0f, 1f) else 0f
            val accelerated = pull * pull * pull * pull
            val travelRadius = startRadius + (particle.endRadius - startRadius) * accelerated
            val edgeOrbit = (1f - pull) * 4.2f
            val tangentDrift = tangent * (
                (particle.wobbleDistance + edgeOrbit) * motionScale *
                    sin(Math.toRadians((particle.phase + elapsedSeconds * 65f).toDouble())).toFloat()
                )
            val radialDrift = radial * (
                particle.wobbleDistance * motionScale * 0.25f *
                    sin(Math.toRadians((particle.secondPhase + elapsedSeconds * 55f).toDouble())).toFloat()
                )
            drawConnectParticle(
                color = particleColor,
                center = center + radial * travelRadius + tangentDrift + radialDrift,
                radius = particle.radius * 2f * (1f - accelerated * 0.67f) * (0.8f + 0.35f * blink),
                alpha = activeAlpha * introAlpha * fadeIn * fadeOut * blink,
            )
        }
    }
}

private data class ParticleSeed(
    val angle: Float,
    val introDelay: Float,
    val cycleSeconds: Float,
    val pullDelay: Float,
    val radius: Float,
    val edgeOffset: Float,
    val endRadius: Float,
    val wobbleDistance: Float,
    val phase: Float,
    val secondPhase: Float,
    val colorDelay: Float,
    val colorIndex: Int,
)

private fun DrawScope.drawConnectParticle(color: Color, center: Offset, radius: Float, alpha: Float) {
    drawCircle(
        color = color.copy(alpha = alpha),
        radius = radius,
        center = center,
    )
}

private fun pseudoRandom(index: Int, salt: Int): Float {
    val value = ((index + 1) * 1103515245 + salt * 12345) and 0x7fffffff
    return (value % 1000) / 1000f
}

private fun Protocol.label(): String = when (this) {
    Protocol.Vless -> "VLESS"
    Protocol.Hysteria2 -> "Hysteria2"
    Protocol.Trojan -> "Trojan"
    Protocol.Vmess -> "VMess"
    Protocol.Shadowsocks -> "Shadowsocks"
    Protocol.Socks -> "SOCKS"
    Protocol.WireGuard -> "WireGuard"
    Protocol.Tuic -> "TUIC"
    Protocol.Hysteria -> "Hysteria"
    Protocol.Naive -> "Naive"
}

@Composable
private fun ProtocolChip(protocol: Protocol) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = protocol.label(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MetricText(value: String, fallbackUnit: String) {
    val (amount, unit) = splitMetric(value, fallbackUnit)
    Row(
        modifier = Modifier.width(76.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = amount,
            modifier = Modifier.width(31.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = unit,
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun ConnectedBorderGlow(
    connected: Boolean,
    eventId: Int,
    consumedEventId: Int,
    onEventConsumed: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fallbackRadius = with(LocalDensity.current) { 28.dp.toPx() }
    val cornerRadii = rememberDisplayCornerRadii(fallbackRadius)
    val progress = remember { Animatable(1f) }
    LaunchedEffect(connected, eventId) {
        if (connected && eventId > consumedEventId) {
            onEventConsumed(eventId)
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1_250, easing = LinearEasing),
            )
        } else {
            progress.snapTo(1f)
        }
    }

    Canvas(modifier = modifier) {
        if (!connected || eventId == 0 || progress.value >= 1f) return@Canvas

        val phase = progress.value
        val opacity = when {
            phase < 0.2f -> 0.3f + phase / 0.2f * 0.7f
            phase > 0.8f -> (1f - phase) / 0.2f
            else -> 1f
        }
        val centerY = size.height * (-0.2f + phase * 1.4f)
        val connectedGreen = Color(0xFF00C07F)
        val trailGlow = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.1f to connectedGreen.copy(alpha = 0.015f),
                0.2f to connectedGreen.copy(alpha = 0.06f),
                0.3f to connectedGreen.copy(alpha = 0.18f),
                0.4f to connectedGreen.copy(alpha = 0.46f),
                0.47f to connectedGreen.copy(alpha = 0.84f),
                0.5f to connectedGreen,
                0.53f to connectedGreen.copy(alpha = 0.84f),
                0.6f to connectedGreen.copy(alpha = 0.46f),
                0.7f to connectedGreen.copy(alpha = 0.18f),
                0.8f to connectedGreen.copy(alpha = 0.06f),
                0.9f to connectedGreen.copy(alpha = 0.015f),
                1f to Color.Transparent,
            ),
            start = Offset(0f, centerY - size.height * 1.26f),
            end = Offset(0f, centerY + size.height * 1.26f),
        )
        val centerGlow = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.14f to connectedGreen.copy(alpha = 0.015f),
                0.28f to connectedGreen.copy(alpha = 0.08f),
                0.38f to connectedGreen.copy(alpha = 0.28f),
                0.46f to connectedGreen.copy(alpha = 0.72f),
                0.5f to connectedGreen,
                0.54f to connectedGreen.copy(alpha = 0.72f),
                0.62f to connectedGreen.copy(alpha = 0.28f),
                0.72f to connectedGreen.copy(alpha = 0.08f),
                0.86f to connectedGreen.copy(alpha = 0.015f),
                1f to Color.Transparent,
            ),
            start = Offset(0f, centerY - size.height * 0.84f),
            end = Offset(0f, centerY + size.height * 0.84f),
        )
        val inset = 2.dp.toPx()
        val borderSize = Size(
            width = size.width - inset * 2f,
            height = size.height - inset * 2f,
        )
        val maximumRadius = minOf(borderSize.width, borderSize.height) / 2f
        fun adjustedRadius(radius: Float): CornerRadius {
            val value = (radius - inset).coerceIn(0f, maximumRadius)
            return CornerRadius(value, value)
        }
        val borderPath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = inset,
                    top = inset,
                    right = size.width - inset,
                    bottom = size.height - inset,
                    topLeftCornerRadius = adjustedRadius(cornerRadii.topLeft),
                    topRightCornerRadius = adjustedRadius(cornerRadii.topRight),
                    bottomRightCornerRadius = adjustedRadius(cornerRadii.bottomRight),
                    bottomLeftCornerRadius = adjustedRadius(cornerRadii.bottomLeft),
                ),
            )
        }
        drawPath(
            path = borderPath,
            brush = centerGlow,
            style = Stroke(width = 24.dp.toPx()),
            alpha = opacity * 0.2f,
        )
        drawPath(
            path = borderPath,
            brush = centerGlow,
            style = Stroke(width = 12.dp.toPx()),
            alpha = opacity * 0.44f,
        )
        drawPath(
            path = borderPath,
            brush = centerGlow,
            style = Stroke(width = 6.dp.toPx()),
            alpha = opacity * 0.72f,
        )
        drawPath(
            path = borderPath,
            brush = trailGlow,
            style = Stroke(width = 3.dp.toPx()),
            alpha = opacity,
        )
    }
}

private data class DisplayCornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
)

@Composable
private fun rememberDisplayCornerRadii(fallbackRadius: Float): DisplayCornerRadii {
    val view = LocalView.current
    val orientation = LocalConfiguration.current.orientation
    val fallback = remember(view, orientation, fallbackRadius) {
        systemDisplayCornerRadii(view, fallbackRadius)
    }
    var radii by remember(view, orientation, fallback) { mutableStateOf(fallback) }

    LaunchedEffect(view, orientation, fallback) {
        withFrameNanos { }
        radii = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            if (insets == null) {
                fallback
            } else {
                DisplayCornerRadii(
                    topLeft = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                        ?.radius?.toFloat() ?: fallback.topLeft,
                    topRight = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                        ?.radius?.toFloat() ?: fallback.topRight,
                    bottomRight = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                        ?.radius?.toFloat() ?: fallback.bottomRight,
                    bottomLeft = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                        ?.radius?.toFloat() ?: fallback.bottomLeft,
                )
            }
        } else {
            fallback
        }
    }

    return radii
}

private fun systemDisplayCornerRadii(view: View, fallbackRadius: Float): DisplayCornerRadii {
    val resources = view.resources
    fun systemDimension(name: String): Float? {
        val id = resources.getIdentifier(name, "dimen", "android")
        return id.takeIf { it != 0 }
            ?.let(resources::getDimensionPixelSize)
            ?.takeIf { it > 0 }
            ?.toFloat()
    }

    val shared = systemDimension("rounded_corner_radius") ?: fallbackRadius
    val top = systemDimension("rounded_corner_radius_top") ?: shared
    val bottom = systemDimension("rounded_corner_radius_bottom") ?: shared
    return DisplayCornerRadii(
        topLeft = top,
        topRight = top,
        bottomRight = bottom,
        bottomLeft = bottom,
    )
}

private fun splitMetric(value: String, fallbackUnit: String): Pair<String, String> {
    if (value == "—") return "—" to fallbackUnit
    val parts = value.trim().split(' ', limit = 2)
    return if (parts.size == 2) parts[0] to parts[1] else value to fallbackUnit
}

@Composable
private fun ProfileSummary(modifier: Modifier = Modifier, profile: VpnProfile?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (profile != null) {
                ProtocolChip(profile.protocol)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    profile?.displayName() ?: "Список пуст",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ExpandMore, contentDescription = localizedText("Открыть список профилей"))
        }
    }
}

@Composable
private fun GroupHeaderRow(
    groupName: String,
    isExpanded: Boolean,
    isSubPage: Boolean,
    hasActive: Boolean,
    profilesCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val headerBg = if (hasActive) {
        Color(0xFF00C07F).copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    val headerBorderColor = if (hasActive) {
        Color(0xFF00C07F).copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    }
    val headerContentColor = if (hasActive) {
        Color(0xFF00C07F)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    }
    val rowModifier = if (isSubPage) {
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    } else {
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ProfileListRowMinHeight)
    }

    Surface(
        onClick = onClick,
        modifier = rowModifier,
        shape = RoundedCornerShape(12.dp),
        color = headerBg,
        border = BorderStroke(1.dp, headerBorderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isSubPage) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = localizedText("Назад"),
                        tint = headerContentColor,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    val rotation by animateFloatAsState(if (isExpanded) 0f else -90f, label = "arrow-rotation")
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = headerContentColor,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = rotation }
                    )
                }
                Text(
                    text = groupName.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = headerContentColor
                )
            }
            Surface(
                color = if (hasActive) Color(0xFF00C07F).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = profilesCount.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = headerContentColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProfilesOverlay(
    profiles: List<VpnProfile>,
    activeIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onShare: (VpnProfile) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val active = profiles.getOrNull(activeIndex)

    val grouped = remember(profiles) {
        profiles.mapIndexed { index, p -> index to p }
            .filter { it.second.group.isNotBlank() }
            .groupBy { it.second.group }
    }
    val ungrouped = remember(profiles) {
        profiles.mapIndexed { index, p -> index to p }
            .filter { it.second.group.isBlank() }
    }

    var activeGroupName by remember { mutableStateOf<String?>(null) }

    // Auto-return if current active group is deleted or becomes empty
    val currentGroupItems = grouped[activeGroupName] ?: emptyList()
    LaunchedEffect(currentGroupItems) {
        if (activeGroupName != null && currentGroupItems.isEmpty()) {
            activeGroupName = null
        }
    }

    // Custom animation states
    var animateIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        animateIn = true
    }

    val scope = rememberCoroutineScope()
    fun dismissWithAnimation() {
        scope.launch {
            animateIn = false
            kotlinx.coroutines.delay(260) // Wait for exit animation to complete
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = {
            if (activeGroupName != null) {
                activeGroupName = null
            } else {
                dismissWithAnimation()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        val alpha by animateFloatAsState(targetValue = if (animateIn) 0.56f else 0f, label = "bg-alpha")
        val offsetTransition by animateFloatAsState(
            targetValue = if (animateIn) 0f else 1f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "sheet-offset"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = alpha))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissWithAnimation() }
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .graphicsLayer {
                        translationY = size.height * offsetTransition
                    }
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {} // Consume click to prevent closing the dialog
                    ),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 1.dp
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val currentGroup = activeGroupName
                    if (currentGroup != null) {
                        val items = grouped[currentGroup] ?: emptyList()
                        stickyHeader(key = "group_header_subpage") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                GroupHeaderRow(
                                    groupName = currentGroup,
                                    isExpanded = true,
                                    isSubPage = true,
                                    hasActive = items.any { it.first == activeIndex },
                                    profilesCount = items.size,
                                    onClick = { activeGroupName = null },
                                    modifier = Modifier.padding(horizontal = 22.dp)
                                )
                            }
                        }
                        items(items, key = { (index, _) -> "group_item_${currentGroup}_$index" }) { (index, profile) ->
                            ProfileRow(
                                profile = profile,
                                selected = index == activeIndex,
                                onClick = { onSelect(index) },
                                onShare = { onShare(profile) },
                                onDelete = { onDelete(index) },
                                edgeToEdgeContent = true,
                                modifier = Modifier.padding(horizontal = 22.dp)
                            )
                        }
                    } else {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Профили", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        active?.displayName() ?: "Список пуст",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { dismissWithAnimation() }) {
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = localizedText("Закрыть список профилей"),
                                    )
                                }
                            }
                        }

                        if (profiles.isEmpty()) {
                            item {
                                Text(
                                    "Нажмите + сверху, чтобы добавить первый профиль.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 22.dp)
                                )
                            }
                        } else {
                            grouped.forEach { (groupName, items) ->
                                item(key = "group_header_$groupName") {
                                    GroupHeaderRow(
                                        groupName = groupName,
                                        isExpanded = false,
                                        isSubPage = false,
                                        hasActive = items.any { it.first == activeIndex },
                                        profilesCount = items.size,
                                        onClick = { activeGroupName = groupName },
                                        modifier = Modifier.padding(horizontal = 22.dp)
                                    )
                                }
                            }
                            items(ungrouped, key = { (index, _) -> "ungrouped_$index" }) { (index, profile) ->
                                ProfileRow(
                                    profile = profile,
                                    selected = index == activeIndex,
                                    onClick = { onSelect(index) },
                                    onShare = { onShare(profile) },
                                    onDelete = { onDelete(index) },
                                    modifier = Modifier.padding(horizontal = 22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: VpnProfile,
    selected: Boolean,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    edgeToEdgeContent: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ProfileListRowMinHeight)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (edgeToEdgeContent) 0.dp else 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProtocolChip(profile.protocol)
        Column(modifier = Modifier.weight(1f)) {
            Text(profile.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${profile.server}:${profile.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Text("выбран", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.Share,
                contentDescription = localizedText("Поделиться профилем"),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = localizedText("Удалить профиль"),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val ProfileListRowMinHeight = 70.dp

@Composable
private fun ShareProfileDialog(profile: VpnProfile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val linkResult = remember(profile) { ProfileLinkSerializer.serialize(profile) }
    val link = linkResult.getOrNull()
    var showQr by remember { mutableStateOf(false) }
    val qrBitmap = remember(link, showQr) {
        if (showQr && link != null) createQrBitmap(link, 720) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.displayName()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (link == null) {
                    Text(
                        text = linkResult.exceptionOrNull()?.message
                            ?: localizedText("Не удалось подготовить профиль"),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (showQr && qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(240.dp),
                        )
                    }
                } else {
                    AddMethodButton(Icons.Default.ContentPaste, "Скопировать в буфер") {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Warpy profile", link))
                        onDismiss()
                    }
                    AddMethodButton(Icons.Default.QrCodeScanner, "Показать QR") {
                        showQr = true
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun AddProfileDialog(
    onImportValue: (String) -> Boolean,
    onScanQr: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var importError by remember { mutableStateOf("") }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onScanQr()
        }
    }
    val qrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val value = decodeQrFromImage(context, uri)
            if (value.isNullOrBlank()) {
                onImportValue("")
            } else {
                if (onImportValue(value)) onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить профиль") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AddMethodButton(Icons.Default.ContentPaste, "Вставить из буфера") {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    val text = item?.text?.toString()
                        ?: item?.uri?.toString()
                        ?: item?.coerceToText(context)?.toString()
                        .orEmpty()
                    if (onImportValue(text)) {
                        onDismiss()
                    } else {
                        importError = if (text.isBlank()) {
                            "Буфер обмена пуст."
                        } else {
                            "Не удалось распознать VPN-профиль."
                        }
                    }
                }
                AddMethodButton(Icons.Default.QrCodeScanner, "Считать QR из изображения") {
                    qrLauncher.launch("image/*")
                }
                AddMethodButton(Icons.Default.CameraAlt, "Сканировать QR камерой") {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                Text(
                    "Тип профиля определяется автоматически.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (importError.isNotBlank()) {
                    Text(
                        importError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun AddMethodButton(icon: ImageVector, text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(text, maxLines = 1)
    }
}

@Composable
private fun QrScannerScreen(onResult: (String) -> Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    val handled = remember { AtomicBoolean(false) }
    var scannerView by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    var hintText by remember { mutableStateOf("Наведите камеру на QR-код профиля") }

    DisposableEffect(lifecycleOwner, scannerView) {
        val view = scannerView
        if (view == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> view.resume()
                    Lifecycle.Event.ON_PAUSE -> view.pause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                view.resume()
            }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                view.pause()
            }
        }
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    DecoratedBarcodeView(viewContext).apply {
                        setStatusText("")
                        barcodeView.decoderFactory = DefaultDecoderFactory(
                            listOf(BarcodeFormat.QR_CODE),
                            mapOf(
                                DecodeHintType.TRY_HARDER to true,
                                DecodeHintType.ALSO_INVERTED to true,
                            ),
                            null,
                            0,
                        )
                        decodeContinuous(object : BarcodeCallback {
                            override fun barcodeResult(result: BarcodeResult?) {
                                val value = result?.text?.trim().orEmpty()
                                if (value.isBlank() || !handled.compareAndSet(false, true)) return
                                ContextCompat.getMainExecutor(context).execute {
                                    val imported = currentOnResult(value)
                                    if (!imported) {
                                        hintText = "QR-код не содержит поддерживаемый VPN-профиль"
                                        handled.set(false)
                                    }
                                }
                            }

                            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) = Unit
                        })
                        scannerView = this
                    }
                },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(22.dp)
                    .size(46.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = localizedText("Назад"),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    text = hintText,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusPage(
    settings: AppSettings,
    diagnostics: Diagnostics,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = settings.profile
    val statusEvents = remember { WarpyService.statusEvents(context) }
    var checkResult by remember(settings.profile, settings.mtu, settings.appTunnelMode, settings.tunneledApps) {
        mutableStateOf("")
    }
    var checkingProfile by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack)
                Text("Состояние", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusRow("Статус", diagnostics.message)
                    StatusRow("Профиль", profile?.displayName().orEmpty().ifBlank { "Нет профиля" })
                    StatusRow("Протокол", profile?.protocol?.label() ?: "—")
                    StatusRow("Сервер", profile?.let { "${it.server}:${it.port}" } ?: "—")
                    StatusRow("Скорость", diagnostics.speedText)
                    StatusRow("Пинг", diagnostics.pingText)
                    StatusRow("MTU", settings.mtu.takeIf { it > 0 }?.toString() ?: "Авто")
                    StatusRow("Туннелирование", appTunnelModeText(settings.appTunnelMode, settings.tunneledApps.size))
                    if (checkResult.isNotBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            checkResult,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    checkingProfile = true
                    checkResult = "Проверяем профиль..."
                    scope.launch {
                        checkResult = withContext(Dispatchers.IO) { checkProfile(settings) }
                        checkingProfile = false
                    }
                },
                enabled = !checkingProfile,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (checkingProfile) "Проверка..." else "Проверить профиль")
            }

            OutlinedButton(
                onClick = {
                    val report = diagnosticReport(settings, diagnostics)
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Warpy diagnostics", report))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Скопировать отчет")
            }

            if (statusEvents.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Последние события", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        val language = LocalWarpyLanguage.current
                        statusEvents.takeLast(6).asReversed().forEach { event ->
                            Text(
                                formatStatusEvent(event, language),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.width(120.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatStatusEvent(event: String, language: AppLanguage): String {
    val parts = event.split('|', limit = 3)
    if (parts.size < 3) return event
    val time = runCatching {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(parts[0].toLong()))
    }.getOrDefault("")
    val status = when (parts[1]) {
        WarpyService.STATUS_CONNECTING -> "Подключение"
        WarpyService.STATUS_CONNECTED -> "Подключено"
        WarpyService.STATUS_STOPPED -> "Выключено"
        WarpyService.STATUS_ERROR -> "Ошибка"
        else -> parts[1]
    }
    return "$time  ${WarpyLocalization.text(status, language)}: " +
        WarpyLocalization.text(parts[2], language)
}

private fun diagnosticReport(settings: AppSettings, diagnostics: Diagnostics): String {
    val profile = settings.profile
    val redactServer = { srv: String ->
        if (srv.isBlank()) ""
        else {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(srv.toByteArray(Charsets.UTF_8))
            "sha256-" + digest.take(4).joinToString("") { "%02x".format(it) } + "..."
        }
    }
    val redactName = { name: String ->
        if (name.isBlank()) ""
        else name.take(2) + "***"
    }
    return buildString {
        appendLine("Warpy diagnostics")
        appendLine("status=${diagnostics.status}")
        appendLine("message=${diagnostics.message}")
        appendLine("profile=${profile?.let { redactName(it.name) }.orEmpty()}")
        appendLine("protocol=${profile?.protocol?.label().orEmpty()}")
        appendLine("server=${profile?.let { redactServer(it.server) }.orEmpty()}:${profile?.port ?: 0}")
        appendLine("speed=${diagnostics.speedText}")
        appendLine("ping=${diagnostics.pingText}")
        appendLine("uptime=${diagnostics.uptimeText}")
        appendLine("mtu=${settings.mtu.takeIf { it > 0 } ?: "auto"}")
        appendLine("stability=${settings.stabilityModeEnabled}")
        appendLine("autoStartOnBoot=${settings.autoStartOnBoot}")
        appendLine("adBlock=${settings.adBlockEnabled}")
        appendLine("blockQuic=${settings.blockQuic}")
        appendLine("bypassLan=${settings.bypassLan}")
        appendLine("appTunnel=${settings.appTunnelMode} selected=${settings.tunneledApps.size}")
    }
}

private fun checkProfile(settings: AppSettings): String {
    val profile = settings.profile ?: return "Профиль не выбран."
    val config = runCatching { SingBoxConfigBuilder.build(settings) }
        .getOrElse { return "Конфиг не собирается: ${it.message.orEmpty().ifBlank { it::class.java.simpleName }}" }
    if (config.isBlank()) return "Конфиг пустой."

    val resolved = runCatching { InetAddress.getAllByName(profile.server).toList() }
        .getOrElse { return "DNS не нашел сервер ${profile.server}: ${it.message.orEmpty().ifBlank { it::class.java.simpleName }}" }
    if (resolved.isEmpty()) return "DNS не вернул адреса для ${profile.server}."

    if (profile.protocol.isUdpBased) {
        return "Базовая проверка пройдена: конфиг собран, DNS нашел ${resolved.first().hostAddress}. Профиль работает по UDP, поэтому окончательная проверка идет при запуске VPN."
    }

    val startedAt = System.nanoTime()
    val tcpResult = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(profile.server, profile.port), PROFILE_CHECK_TIMEOUT_MS)
        }
    }
    return tcpResult.fold(
        onSuccess = {
            val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(1)
            "Базовая проверка пройдена: конфиг собран, DNS работает, TCP ${profile.server}:${profile.port} отвечает за ${elapsedMs} мс."
        },
        onFailure = {
            "Сервер ${profile.server}:${profile.port} не отвечает по TCP: ${it.message.orEmpty().ifBlank { it::class.java.simpleName }}"
        },
    )
}

private fun appTunnelModeText(mode: AppTunnelMode, count: Int): String = when (mode) {
    AppTunnelMode.All -> "Все приложения"
    AppTunnelMode.Include -> "Только выбранные: $count"
    AppTunnelMode.Exclude -> "Исключить выбранные: $count"
}

private fun updateSettingsSubtitle(state: UpdateUiState): String = when (state.stage) {
    UpdateStage.Checking -> "Проверяем обновления..."
    UpdateStage.Available -> "Доступна версия ${state.version}"
    UpdateStage.Downloading -> "Скачиваем обновление"
    UpdateStage.Error -> "Не удалось проверить обновления"
    else -> "Установлена версия ${BuildConfig.VERSION_NAME}"
}

@Composable
private fun SettingsPage(
    adBlockEnabled: Boolean,
    blockQuic: Boolean,
    bypassLan: Boolean,
    stabilityModeEnabled: Boolean,
    autoStartOnBoot: Boolean,
    language: AppLanguage,
    resolvedLanguage: AppLanguage,
    updateState: UpdateUiState,
    mtu: Int,
    onAdBlock: (Boolean) -> Unit,
    onBlockQuic: (Boolean) -> Unit,
    onBypassLan: (Boolean) -> Unit,
    onStabilityMode: (Boolean) -> Unit,
    onAutoStartOnBoot: (Boolean) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onMtu: (Int) -> Unit,
    onOpenStatus: () -> Unit,
    onOpenAppTunneling: () -> Unit,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val batteryUnrestricted = remember { isIgnoringBatteryOptimizations(context) }
    var advancedExpanded by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack)
                Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SettingsSection("Подключение") {
                    SettingAction(
                        title = "Туннелирование",
                        subtitle = "Отдельные правила для приложений и сайтов",
                        icon = Icons.Default.Dns,
                        onClick = onOpenAppTunneling,
                    )
                    SettingsItemDivider()
                    SettingSwitch(
                        "Стабильное соединение",
                        "Автоматически восстанавливать VPN после сна и смены сети",
                        stabilityModeEnabled,
                        onStabilityMode,
                        Icons.Default.WifiTethering,
                    )
                    SettingsItemDivider()
                    SettingSwitch(
                        "Запуск вместе с телефоном",
                        "Включать VPN после перезагрузки, если он работал",
                        autoStartOnBoot,
                        onAutoStartOnBoot,
                        Icons.Default.PowerSettingsNew,
                    )
                }

                SettingsSection("Защита") {
                    SettingSwitch(
                        "Блокировать рекламу",
                        "Скрывать базовую рекламу и трекеры через DNS",
                        adBlockEnabled,
                        onAdBlock,
                        Icons.Default.Security,
                    )
                    SettingsItemDivider()
                    SettingSwitch(
                        "Доступ к домашней сети",
                        "Открывать роутер и устройства в локальной сети напрямую",
                        bypassLan,
                        onBypassLan,
                        Icons.Default.WifiTethering,
                    )
                }

                SettingsSection("Фоновая работа") {
                    SettingAction(
                        title = "Ограничения батареи",
                        subtitle = if (batteryUnrestricted) {
                            "Warpy может работать в фоне без ограничений"
                        } else {
                            "Разрешить Android не останавливать VPN"
                        },
                        icon = Icons.Default.PowerSettingsNew,
                        onClick = { openBatteryOptimizationSettings(context) },
                    )
                    SettingsItemDivider()
                    SettingAction(
                        title = "Постоянный VPN",
                        subtitle = "Закрепить Warpy в системных настройках Android",
                        icon = Icons.Default.Security,
                        onClick = { openVpnSettings(context) },
                    )
                }

                SettingsSection("Дополнительно") {
                    SettingsExpandableHeader(
                        expanded = advancedExpanded,
                        onClick = { advancedExpanded = !advancedExpanded },
                    )
                    AnimatedVisibility(
                        visible = advancedExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column {
                            SettingsItemDivider()
                            SettingSwitch(
                                "Совместимость QUIC",
                                "Использовать TCP вместо QUIC и HTTP/3 при проблемах с сайтами",
                                blockQuic,
                                onBlockQuic,
                                Icons.Default.Dns,
                            )
                            SettingsItemDivider()
                            MtuSetting(mtu = mtu, onMtu = onMtu)
                        }
                    }
                }

                SettingAction(
                    title = "Состояние и диагностика",
                    subtitle = "Подробности подключения и отчет для диагностики",
                    icon = Icons.Default.Dns,
                    onClick = onOpenStatus,
                )

                LanguageSetting(
                    language = language,
                    resolvedLanguage = resolvedLanguage,
                    onLanguage = onLanguage,
                )

                SettingAction(
                    title = "Проверить обновления",
                    subtitle = updateSettingsSubtitle(updateState),
                    icon = Icons.Default.Settings,
                    onClick = onCheckUpdates,
                )

                Text(
                    text = "Warpy ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun LanguageSetting(
    language: AppLanguage,
    resolvedLanguage: AppLanguage,
    onLanguage: (AppLanguage) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val activeLabel = when (resolvedLanguage) {
        AppLanguage.Russian -> "Русский"
        else -> "English"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { showDialog = true }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Язык",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = SETTINGS_TITLE_GREEN,
        )
        Text(activeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDialog) {
        val options = listOf(
            AppLanguage.Russian to "Русский",
            AppLanguage.English to "English",
        )
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Язык") },
            text = {
                Column {
                    options.forEach { (option, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onLanguage(option)
                                    showDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, modifier = Modifier.weight(1f))
                            if (language == option) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            text = title,
            modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = SETTINGS_TITLE_GREEN,
        )
        Column(content = content)
        HorizontalDivider(
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SettingsItemDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 38.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun SettingsExpandableHeader(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Сетевые параметры",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = SETTINGS_TITLE_GREEN,
            )
            Text(
                "MTU и совместимость соединения",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = localizedText(if (expanded) "Свернуть" else "Развернуть"),
            modifier = Modifier
                .size(22.dp)
                .rotate(if (expanded) 180f else 0f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class TunnelSettingsTab {
    Apps,
    Sites,
}

@Composable
private fun TunnelSettingsTabs(
    selected: TunnelSettingsTab,
    onSelected: (TunnelSettingsTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        listOf(
            TunnelSettingsTab.Apps to "Приложения",
            TunnelSettingsTab.Sites to "Сайты",
        ).forEach { (tab, label) ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelected(tab) },
                color = if (selected == tab) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected == tab) SETTINGS_TITLE_GREEN else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected == tab) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun AppTunnelingPage(
    mode: AppTunnelMode,
    selectedApps: Set<String>,
    siteMode: AppTunnelMode,
    selectedSites: Set<String>,
    onMode: (AppTunnelMode) -> Unit,
    onToggleApp: (String) -> Unit,
    onSiteMode: (AppTunnelMode) -> Unit,
    onAddSite: (String) -> Unit,
    onRemoveSite: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var excludeSystem by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(TunnelSettingsTab.Apps) }
    var siteInput by remember { mutableStateOf("") }

    LaunchedEffect(excludeSystem) {
        loadingApps = true
        apps = withContext(Dispatchers.IO) {
            loadInstalledApps(context.applicationContext, includeSystem = !excludeSystem)
        }
        loadingApps = false
    }

    val visibleApps = remember(apps, query, excludeSystem, selectedApps) {
        apps.asSequence()
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .toList()
            .sortedByDescending { it.packageName in selectedApps }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack)
                Text("Туннелирование", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }

            TunnelSettingsTabs(selected = selectedTab, onSelected = { selectedTab = it })

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    if (selectedTab == TunnelSettingsTab.Apps) {
                        AppTunnelModeDropdown(mode = mode, onMode = onMode, target = selectedTab)
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            placeholder = { Text("Поиск") },
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { excludeSystem = !excludeSystem },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(checked = excludeSystem, onCheckedChange = { excludeSystem = it })
                            Text("Исключить системные", style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        when {
                            loadingApps -> Text("Загрузка приложений...", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            visibleApps.isEmpty() -> Text("Ничего не найдено", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(visibleApps, key = { it.packageName }) { app ->
                                    AppTunnelRow(app, app.packageName in selectedApps) { onToggleApp(app.packageName) }
                                }
                            }
                        }
                    } else {
                        AppTunnelModeDropdown(mode = siteMode, onMode = onSiteMode, target = selectedTab)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = siteInput,
                                onValueChange = { siteInput = it },
                                modifier = Modifier.weight(1f).height(52.dp),
                                singleLine = true,
                                shape = RoundedCornerShape(18.dp),
                                placeholder = { Text("example.com") },
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(
                                onClick = {
                                    onAddSite(siteInput)
                                    siteInput = ""
                                },
                                enabled = siteInput.isNotBlank(),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = localizedText("Добавить сайт"))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (selectedSites.isEmpty()) {
                            Text("Добавьте сайты для отдельного правила", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(selectedSites.sorted(), key = { it }) { site ->
                                    SiteTunnelRow(site = site, onRemove = { onRemoveSite(site) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTunnelModeDropdown(
    mode: AppTunnelMode,
    onMode: (AppTunnelMode) -> Unit,
    target: TunnelSettingsTab,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable { expanded = true },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(mode.title(target), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(mode.subtitle(target), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = localizedText("Выбрать режим туннелирования"),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(AppTunnelMode.Exclude, AppTunnelMode.Include, AppTunnelMode.All).forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.title(target)) },
                    onClick = {
                        onMode(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun AppTunnelMode.title(target: TunnelSettingsTab): String = when (this) {
    AppTunnelMode.All -> if (target == TunnelSettingsTab.Apps) "Все приложения" else "Все сайты"
    AppTunnelMode.Include -> "Только выбранные"
    AppTunnelMode.Exclude -> "Исключить выбранные"
}

private fun AppTunnelMode.subtitle(target: TunnelSettingsTab): String = when (this) {
    AppTunnelMode.All -> if (target == TunnelSettingsTab.Apps) "VPN работает для всего телефона" else "Все сайты используют VPN"
    AppTunnelMode.Include -> if (target == TunnelSettingsTab.Apps) "Через VPN идут только отмеченные приложения" else "Через VPN идут только добавленные сайты"
    AppTunnelMode.Exclude -> if (target == TunnelSettingsTab.Apps) "Отмеченные приложения идут напрямую" else "Добавленные сайты идут напрямую"
}

@Composable
private fun AppTunnelRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        AppIcon(app.packageName)
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SiteTunnelRow(site: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(site, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Delete,
                contentDescription = localizedText("Удалить сайт"),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(packageName) {
        icon = withContext(Dispatchers.IO) {
            loadAppIcon(context.applicationContext.packageManager, packageName)
        }
    }

    val modifier = Modifier
        .size(38.dp)
        .clip(RoundedCornerShape(10.dp))
    val bitmap = icon
    if (bitmap == null) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh) {}
    } else {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    }
}

@Composable
private fun MtuSetting(mtu: Int, onMtu: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Default.Dns,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("MTU", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = SETTINGS_TITLE_GREEN)
                Text(
                    "Размер сетевых пакетов. Изменение применится при следующем подключении",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0 to "Авто", 1280 to "1280", 1400 to "1400", 1500 to "1500").forEach { (value, label) ->
                OutlinedButton(
                    onClick = { onMtu(value) },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(
                        1.dp,
                        if (mtu == value) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = SETTINGS_TITLE_GREEN)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = LocalContentColor.current,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = SETTINGS_TITLE_GREEN)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.72f),
            )
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatteryOptimizationSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val requestIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    runCatching { context.startActivity(requestIntent) }
        .recoverCatching { context.startActivity(fallbackIntent) }
}

private fun openVpnSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}

private data class InstalledApp(
    val label: String,
    val packageName: String,
    val system: Boolean,
)

@Suppress("DEPRECATION")
private fun loadInstalledApps(context: Context, includeSystem: Boolean): List<InstalledApp> {
    val packageManager = context.packageManager
    return packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
        .mapNotNull { appInfo ->
            val packageName = appInfo.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            val system = appInfo.isBundledSystemApp()
            if (!includeSystem && system) return@mapNotNull null
            InstalledApp(
                label = appInfo.loadLabel(packageManager).toString().ifBlank { packageName },
                packageName = packageName,
                system = system,
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private fun loadAppIcon(packageManager: PackageManager, packageName: String): ImageBitmap =
    runCatching {
        packageManager.getApplicationIcon(packageName)
            .toBitmap(width = APP_ICON_SIZE_PX, height = APP_ICON_SIZE_PX)
            .asImageBitmap()
    }.getOrElse {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

private fun ApplicationInfo.isBundledSystemApp(): Boolean {
    val systemApp = flags and ApplicationInfo.FLAG_SYSTEM != 0
    val updatedSystemApp = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    return systemApp && !updatedSystemApp
}

private const val APP_ICON_SIZE_PX = 96

private fun decodeQrFromImage(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bitmap = BitmapFactory.decodeStream(input) ?: return@runCatching null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().decode(binaryBitmap).text
    }
}.getOrNull()

private fun createQrBitmap(value: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

private fun VpnProfile.safeTitle(): String {
    val protocolName = when (protocol) {
        Protocol.Vless -> "VLESS"
        Protocol.Hysteria2 -> "Hysteria2"
        Protocol.Trojan -> "Trojan"
        Protocol.Vmess -> "VMess"
        Protocol.Shadowsocks -> "Shadowsocks"
        Protocol.Socks -> "SOCKS"
        Protocol.WireGuard -> "WireGuard"
        Protocol.Tuic -> "TUIC"
        Protocol.Hysteria -> "Hysteria"
        Protocol.Naive -> "Naive"
    }
    val location = if (name.isNotBlank()) name else if (sni.isNotBlank()) sni else server
    return "$protocolName • $location"
}

private fun VpnProfile.displayName(): String =
    name.ifBlank { sni.ifBlank { server } }

private const val PROFILE_CHECK_TIMEOUT_MS = 2500

class WarpStarCompose(
    val angle: Float,
    var r: Float,
    var prevR: Float,
    val speed: Float,
    val baseColor: Color,
    val width: Float,
    val maxR: Float
)

private fun spawnStar(randomStart: Boolean, uploadMode: Boolean): WarpStarCompose {
    val angle = kotlin.random.Random.nextFloat() * 360f
    val r = if (randomStart) (kotlin.random.Random.nextFloat() * 48f + 52f) else (kotlin.random.Random.nextFloat() * 5f + 52f)
    val rand = kotlin.random.Random.nextFloat()
    val baseColor = when {
        uploadMode -> if (rand < 0.18f) Color(0xFF4DA3FF) else Color.White
        else -> if (rand < 0.18f) Color(0xFF00C07F) else Color.White
    }
    return WarpStarCompose(
        angle = angle,
        r = r,
        prevR = r,
        speed = kotlin.random.Random.nextFloat() * 1.2f + 0.4f,
        baseColor = baseColor,
        width = kotlin.random.Random.nextFloat() * 1.5f + 0.8f,
        maxR = 100f + kotlin.random.Random.nextFloat() * 40f
    )
}

@Composable
fun WarpStarfield(
    running: Boolean,
    uploadMode: Boolean,
    valueMbps: Float,
    modifier: Modifier = Modifier
) {
    if (!running) return

    val maxStars = 45
    val stars = remember(uploadMode) {
        MutableList(maxStars) { spawnStar(randomStart = true, uploadMode = uploadMode) }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "warp-infinite")
    val elapsedMillis by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "warp-elapsed"
    )

    val speedFactor = (0.5f + (valueMbps / 300f).coerceAtMost(1f) * 4.5f) * 1.5f

    Canvas(modifier = modifier.fillMaxSize()) {
        val dummy = elapsedMillis // Read to trigger redraw on every frame tick!
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = (min(size.width, size.height) / 2f) / 100f

        stars.forEachIndexed { i, s ->
            s.prevR = s.r
            s.r += s.speed * speedFactor

            if (s.r > 52f) {
                val headR = s.r * scale
                // Tail length multiplier grows dynamically as speed grows to make streaks look much longer
                val tailLengthMultiplier = 3.6f + (valueMbps / 150f).coerceAtMost(1f) * 6.5f
                // Clamp tailR to 42f so the tail never overlaps the center indicators and is hidden during fade-in
                val tailR = max(42f, s.r - (s.r - s.prevR) * tailLengthMultiplier) * scale

                val fadeIn = ((s.r - 52f) / 12f).coerceIn(0f, 1f)
                val fadeOut = (1f - (s.r - (s.maxR - 12f)) / 12f).coerceIn(0f, 1f)
                val alpha = min(fadeIn, fadeOut) * 0.85f

                if (alpha > 0.01f) {
                    val angleRad = Math.toRadians(s.angle.toDouble())
                    val x1 = cx + cos(angleRad).toFloat() * headR
                    val y1 = cy + sin(angleRad).toFloat() * headR
                    val x2 = cx + cos(angleRad).toFloat() * tailR
                    val y2 = cy + sin(angleRad).toFloat() * tailR

                    drawLine(
                        color = s.baseColor.copy(alpha = alpha),
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = s.width.dp.toPx() * 0.84f,
                        cap = StrokeCap.Round
                    )
                }
            }

            if (s.r > s.maxR) {
                stars[i] = spawnStar(randomStart = false, uploadMode = uploadMode)
            }
        }
    }
}
