package com.warpy.app.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.warpy.app.MainActivity
import com.warpy.app.R
import com.warpy.app.data.SettingsStore
import com.warpy.app.localization.WarpyLocalization

class WarpyTileService : TileService() {
    private var statusReceiver: BroadcastReceiver? = null

    override fun onStartListening() {
        super.onStartListening()
        registerStatusReceiver()
        refreshTile()
        startService(Intent(this, WarpyService::class.java).setAction(WarpyService.ACTION_QUERY_STATUS))
    }

    override fun onStopListening() {
        statusReceiver?.let { runCatching { unregisterReceiver(it) } }
        statusReceiver = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            if (WarpyService.isActiveOrStarting(this) || WarpyService.shouldBeRunning(this)) {
                startService(Intent(this, WarpyService::class.java).setAction(WarpyService.ACTION_STOP))
            } else {
                when (VpnStartHelper.startFromSavedState(this)) {
                    VpnLaunchResult.Started -> Unit
                    else -> openApp()
                }
            }
            refreshTile()
        }
    }

    private fun registerStatusReceiver() {
        if (statusReceiver != null) return
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getStringExtra(WarpyService.EXTRA_STATUS)
                qsTile?.state = if (WarpyService.isActiveStatus(status)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                qsTile?.updateTile()
            }
        }
        val filter = IntentFilter(WarpyService.ACTION_STATUS)
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun refreshTile() {
        val publication = WarpyService.currentSessionPublication(this)
        val active = WarpyService.isActiveStatus(publication.status.wireValue)
        val language = runCatching { SettingsStore(this).load().language }.getOrNull()
        qsTile?.apply {
            label = getString(R.string.quick_tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val status = if (active) "Включен" else "Выключен"
                subtitle = language?.let { WarpyLocalization.text(status, it) }
                    ?: getString(if (active) R.string.quick_tile_on else R.string.quick_tile_off)
            }
            state = if (active) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            icon = Icon.createWithResource(this@WarpyTileService, R.drawable.ic_qs_warpy)
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
