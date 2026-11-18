package com.example.bililivealarm
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_MONITORING", false)) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BiliLiveAlarm::ReceiverWakeLock")
            wl.acquire(15000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val serviceIntent = Intent(context, LiveMonitorService::class.java).apply {
            action = "ACTION_POLL_NOW"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}