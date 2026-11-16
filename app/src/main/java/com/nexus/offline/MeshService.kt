package com.nexus.offline

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps the process (and therefore the mesh node) alive while the app is in the background. */
class MeshService : Service() {

    private var baseNotification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun fgType(callActive: Boolean): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (callActive && Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return t
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("nexus", "Mesh node", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH))
        }
        val n = NotificationCompat.Builder(this, "nexus")
            .setContentTitle("NEXUS Offline")
            .setContentText("Mesh node is active")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openIntent())
            .build()
        baseNotification = n
        ServiceCompat.startForeground(this, 1, n, fgType(false))

        val engine = NexusApp.engine
        engine.callHook = { active ->
            try {
                baseNotification?.let { ServiceCompat.startForeground(this, 1, it, fgType(active)) }
            } catch (e: Exception) {
                // microphone service type not allowed right now; call still works while the app is open
            }
        }
        engine.ringHook = { who -> ring(who) }
        engine.msgHook = { who, isFile -> notifyMessage(who, isFile) }
    }

    private fun notifyMessage(who: String, isFile: Boolean) {
        val n = NotificationCompat.Builder(this, "messages")
            .setContentTitle(who)
            .setContentText(if (isFile) "Sent you a file" else "New message")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(3, n)
        } catch (e: Exception) {
            // notifications not permitted
        }
    }

    private fun ring(who: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (who == null) {
            nm.cancel(2)
            return
        }
        val n = NotificationCompat.Builder(this, "calls")
            .setContentTitle("Incoming call")
            .setContentText(who)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        try {
            nm.notify(2, n)
        } catch (e: Exception) {
            // notifications not permitted
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSharedPreferences("nexus", MODE_PRIVATE).edit().putBoolean("autostart", true).apply()
        NexusApp.engine.start()
        return START_STICKY
    }

    override fun onDestroy() {
        NexusApp.engine.callHook = null
        NexusApp.engine.ringHook = null
        NexusApp.engine.msgHook = null
        NexusApp.engine.stop()
        super.onDestroy()
    }
}
