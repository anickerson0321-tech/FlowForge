package com.privatevoice.app.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.privatevoice.app.PrivateVoiceApp
import com.privatevoice.app.R
import com.privatevoice.app.audio.PlaybackState
import com.privatevoice.app.reader.ReadResult

/**
 * Quick Settings tile: tap to read the latest message, tap again to stop.
 * When the phone is locked the user must unlock first, so messages are never
 * read from the lock screen by accident.
 */
class ReadLatestTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val g = PrivateVoiceApp.graph(this)
        val action = Runnable {
            if (g.player.state.value !is PlaybackState.Idle) {
                g.reader.stop()
            } else {
                g.startPlaybackService()
                if (g.reader.readLatest() == ReadResult.NOTHING_PENDING) g.toast("No messages waiting")
            }
            refresh()
        }
        if (isLocked) unlockAndRun(action) else action.run()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val g = PrivateVoiceApp.graph(this)
        val playing = g.player.state.value !is PlaybackState.Idle
        val pending = g.repository.state.value.count
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_voice)
        tile.label = if (playing) "Stop reading" else "Read message"
        tile.subtitle = when {
            playing -> "Reading…"
            pending > 0 -> "$pending waiting"
            else -> "Nothing waiting"
        }
        tile.state = if (playing || pending > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
