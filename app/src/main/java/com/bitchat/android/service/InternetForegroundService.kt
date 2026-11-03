package com.bitchat.android.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.onboarding.NetworkSetupPreferences

/** User-started encrypted messaging connection; independent of Bluetooth/location permissions. */
class InternetForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!NetworkSetupPreferences.from(this).completed) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Подключение panchat", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("panchat").setContentText("Фоновое подключение к сообщениям")
            .setContentIntent(open).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_SECRET).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, note)
        // Process-owned Nostr runtime already owns connections. Never silently reopen after death.
        return START_NOT_STICKY
    }
    override fun onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    companion object {
        private const val CHANNEL = "bitchat_internet_connection"
        private const val ID = 10003
        fun start(context: Context) { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, InternetForegroundService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, InternetForegroundService::class.java)) }
    }
}
