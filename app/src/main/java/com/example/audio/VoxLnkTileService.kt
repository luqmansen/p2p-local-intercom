package com.example.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity

class VoxLnkTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = VoxService.isServiceRunning

        if (isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "VoxLnk: Live"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Connected / Active"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "VoxLnk: Idle"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Tap to launch"
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = VoxService.isServiceRunning

        if (isRunning) {
            // Stop the walkie talkie service directly!
            val serviceIntent = Intent(this, VoxService::class.java).apply {
                action = VoxService.ACTION_STOP
            }
            startService(serviceIntent)
        } else {
            // Launch main activity to connect
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        updateTileState()
    }
}
