package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity

class VoxService : Service() {

    companion object {
        private const val TAG = "VoxService"
        private const val CHANNEL_ID = "vox_service_channel"
        private const val NOTIFICATION_ID = 8881
        
        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val EXTRA_STATUS = "com.example.extra.STATUS"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VoxService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")
        
        if (action == ACTION_START) {
            val statusText = intent.getStringExtra(EXTRA_STATUS) ?: "Channel Active"
            startForegroundServiceWithNotification(statusText)
            acquireLocks()
        } else if (action == ACTION_STOP) {
            stopServiceGracefully()
        }
        
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification(statusText: String) {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshVoice Walkie-Talkie")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // standard fallback icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "startForeground success")
        } catch (e: Exception) {
            Log.e(TAG, "Error in startForeground", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ex: Exception) {
                Log.e(TAG, "Double error startForeground", ex)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "MeshVoice Walkie-Talkie Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoxLnk:WakeLock").apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 1000L) // 10 minutes timeout as safety, or acquire()
                }
                Log.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoxLnk:WifiLock")
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "VoxLnk:WifiLock")
                }.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "WifiLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wifi lock", e)
        }
    }

    private fun stopServiceGracefully() {
        Log.d(TAG, "Stopping service and releasing locks")
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wifi lock", e)
        }

        stopForeground(true)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServiceGracefully()
        super.onDestroy()
        Log.d(TAG, "VoxService destroyed")
    }
}
