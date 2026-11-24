package com.example.bililivealarm
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_MONITORING", false)) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BiliLiveAlarm::AlarmWake")
        try {
            wl.acquire(15000L)
        } catch (e: Exception) {}
        val interval = prefs.getInt("POLL_INTERVAL_SECONDS", 20).coerceAtLeast(5)
        scheduleNextAlarm(context, interval + 5)
        val serviceIntent = Intent(context, LiveMonitorService::class.java).apply {
            action = "ACTION_POLL_NOW"
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            val pendingResult = goAsync()
            val exec = Executors.newSingleThreadExecutor()
            exec.execute {
                try {
                    checkInReceiverFallback(context)
                } finally {
                    try {
                        if (wl.isHeld) wl.release()
                    } catch (ex: Exception) {}
                    exec.shutdown()
                    pendingResult.finish()
                }
            }
        }
    }
    private fun scheduleNextAlarm(context: Context, delaySeconds: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.bililivealarm.ACTION_ALARM_POLL"
        }
        val pi = PendingIntent.getBroadcast(
            context, 1001, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerTime = System.currentTimeMillis() + delaySeconds * 1000L
        try {
            val showIntent = Intent(context, MainActivity::class.java)
            val showPi = PendingIntent.getActivity(
                context, 1002, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerTime, showPi), pi)
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi)
        }
    }
    private fun checkInReceiverFallback(context: Context) {
        val prefs = context.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("STREAMER_LIST", "[]") ?: "[]")
        if (arr.length() == 0) return
        val queryBuilder = StringBuilder("https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids?")
        for (i in 0 until arr.length()) {
            val uid = arr.getJSONObject(i).optString("uid")
            if (uid.isNotEmpty()) queryBuilder.append("uids[]=").append(uid).append("&")
        }
        queryBuilder.append("_t=").append(System.currentTimeMillis())
        try {
            val conn = URL(queryBuilder.toString()).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.useCaches = false
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://live.bilibili.com/")
            conn.setRequestProperty("Connection", "close")
            if (conn.responseCode == 200) {
                val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(text)
                if (json.optInt("code") == 0) {
                    val dataObj = json.optJSONObject("data")
                    val notifiedSet = (prefs.getStringSet("NOTIFIED_UIDS", emptySet()) ?: emptySet()).toMutableSet()
                    val newlyLiveNames = mutableListOf<String>()
                    var firstLive: JSONObject? = null
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val uid = item.optString("uid")
                        val info = dataObj?.optJSONObject(uid)
                        val liveStatus = info?.optInt("live_status", 0) ?: 0
                        val liveTime = info?.optString("live_time", "0")?.trim() ?: "0"
                        val sessionKey = "${uid}_${liveTime}"
                        item.put("live_status", liveStatus)
                        if (info != null) {
                            item.put("uname", info.optString("uname", item.optString("uname")))
                            item.put("title", info.optString("title", ""))
                            item.put("room_id", info.optString("room_id", item.optString("room_id")))
                            val face = info.optString("face", "")
                            if (face.isNotEmpty()) item.put("face", face)
                        }
                        if (liveStatus == 1) {
                            if (!notifiedSet.contains(sessionKey)) {
                                notifiedSet.removeAll { it.startsWith("${uid}_") || it == uid }
                                notifiedSet.add(sessionKey)
                                newlyLiveNames.add(item.optString("uname"))
                                if (firstLive == null) firstLive = item
                            }
                        } else {
                            notifiedSet.removeAll { it.startsWith("${uid}_") || it == uid }
                        }
                    }
                    prefs.edit()
                        .putString("STREAMER_LIST", arr.toString())
                        .putStringSet("NOTIFIED_UIDS", notifiedSet)
                        .commit()
                    if (firstLive != null) {
                        val allNames = newlyLiveNames.joinToString("、")
                        val title = firstLive.optString("title", "正在直播中")
                        val uid = firstLive.optString("uid", "")
                        val roomId = firstLive.optString("room_id", "")
                        val face = firstLive.optString("face", "")
                        try {
                            val triggerIntent = Intent(context, LiveMonitorService::class.java).apply {
                                action = "ACTION_TRIGGER_FROM_RECEIVER"
                                putExtra("UNAME", allNames)
                                putExtra("TITLE", title)
                                putExtra("FACE", face)
                                putExtra("UID", uid)
                                putExtra("ROOM_ID", roomId)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(triggerIntent)
                            } else {
                                context.startService(triggerIntent)
                            }
                        } catch (ex: Exception) {
                            sendFallbackAlarmNotification(context, allNames, title, uid, roomId, face)
                        }
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun sendFallbackAlarmNotification(context: Context, uname: String, title: String, uid: String, roomId: String, face: String) {
        val prefs = context.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        val alertMode = prefs.getInt("ALERT_MODE", 0)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = if (alertMode == 1) "BILI_ALARM_FALLBACK_VIB_CH" else "BILI_ALARM_FALLBACK_SOUND_CH"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                if (alertMode == 1) "后台震动开播提醒" else "后台响铃开播提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                if (alertMode == 1) {
                    setSound(null, null)
                } else {
                    val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(alarmUri, attrs)
                }
                enableVibration(alertMode == 0 || alertMode == 1)
                if (alertMode == 0 || alertMode == 1) {
                    vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                }
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
        val popupIntent = Intent(context, AlarmPopupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("UNAME", uname)
            putExtra("TITLE", title)
            putExtra("FACE", face)
            putExtra("UID", uid)
            putExtra("ROOM_ID", roomId)
        }
        val fullScreenPi = PendingIntent.getActivity(
            context, 22, popupIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("$uname 开播啦！")
            .setContentText("直播间：$title")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(fullScreenPi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setAutoCancel(true)
            .build()
        nm.notify(2002, notification)
    }
}