package com.nexus.offline

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Restarts the mesh node after a phone restart so the relay chain works without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("nexus", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", false)) return
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            need += Manifest.permission.BLUETOOTH_SCAN
            need += Manifest.permission.BLUETOOTH_ADVERTISE
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (need.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        } catch (e: Exception) {
            // the system may refuse a background start; the node starts the next time the app is opened
        }
    }
}
