package com.flowforge.dictation.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.flowforge.dictation.overlay.DictationService
import com.flowforge.dictation.ui.BubbleLauncherActivity

/**
 * Quick Settings tile: one tap shows or hides the dictation bubble from anywhere.
 * Showing goes through [BubbleLauncherActivity] so the microphone foreground service is started
 * from a visible activity, as Android requires.
 */
class BubbleTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (DictationService.isRunning) {
            DictationService.hide(this)
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
            return
        }
        val intent = Intent(this, BubbleLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (DictationService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (DictationService.isRunning) "Bubble on" else "Bubble off"
        tile.updateTile()
    }
}
