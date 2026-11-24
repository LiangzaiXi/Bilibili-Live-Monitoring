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
import java.util.concurrent.atomic.AtomicBoolean
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        private val networkExecutor = Executors.newSingleThreadExecutor()
        private val isChecking = AtomicBoolean(false)
        fun scheduleNextAlarm(context: Context, delaySeconds: Int) {
            val prefs = context.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("IS_MONITORING", false)) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val nextIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.example.bililivealarm.ACTION_ALARM_POLL"
            }
            val pi = PendingIntent.getBroadcast(
                context, 1001, nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerTime = System.currentTimeMillis() + delaySeconds.coerceAtLeast(5) * 1000L
            try {
                val showIntent = Intent(context, MainActivity::class.java)
                val showPi = PendingIntent.getActivity(
                    context, 1002, showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerTime, showPi), pi)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pi)
                }
            }
        }
        fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val nextIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.example.bililivealarm.ACTION_ALARM_POLL"
            }
            val pi = PendingIntent.getBroadcast(
                context, 1001, nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pi)
        }
        fun checkNowAsync(context: Context) {
            val appCtx = context.applicationContext
            if (isChecking.compareAndSet(false, true)) {
                networkExecutor.execute {
                    try {
                        checkStreamersSync(appCtx)
                    } finally {
                        isChecking.set(false)
                    }
                }
            }
        }
        fun triggerAlarm(context: Context, uname: String, title: String, face: String, uid: String, roomId: String) {
            val appCtx = context.applicationContext
            val serviceIntent = Intent(appCtx, LiveMonitorService::class.java).apply {
                action = "ACTION_START_ALARM"
                putExtra("UNAME", uname)
                putExtra("TITLE", title)
                putExtra("FACE", face)
                putExtra("UID", uid)
                putExtra("ROOM_ID", roomId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(serviceIntent)
                } else {
                    appCtx.startService(serviceIntent)
                }
            } catch (e: Exception) {
                val prefs = appCtx.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("IS_RINGING", true)
                    .putString("RINGING_UNAME", uname)
                    .putString("RINGING_TITLE", title)
                    .putString("RINGING_FACE", face)
                    .putString("RINGING_UID", uid)
                    .putString("RINGING_ROOM_ID", roomId)
                    .commit()
                appCtx.sendBroadcast(Intent("com.example.bililivealarm.ACTION_STATUS_UPDATED").setPackage(appCtx.packageName))
                wakeScreen(appCtx)
                sendFallbackAlarmNotification(appCtx, uname, title, uid, roomId, face)
            }
        }
        private fun checkStreamersSync(context: Context) {
            val prefs = context.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
            val snapshotArr = JSONArray(prefs.getString("STREAMER_LIST", "[]") ?: "[]")
            if (snapshotArr.length() == 0) {
                LiveMonitorService.stopAlarmImmediately(context)
                return
            }
            val queryBuilder = StringBuilder("https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids?")
            val uidList = mutableListOf<String>()
            for (i in 0 until snapshotArr.length()) {
                val uid = snapshotArr.getJSONObject(i).optString("uid")
                if (uid.isNotEmpty()) {
                    uidList.add(uid)
                    queryBuilder.append("uids[]=").append(uid).append("&")
                }
            }
            if (uidList.isEmpty()) return
            queryBuilder.append("_t=").append(System.currentTimeMillis())
            try {
                val conn = URL(queryBuilder.toString()).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.useCaches = false
                conn.connectTimeout = 4500
                conn.readTimeout = 4500
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                conn.setRequestProperty("Referer", "https://live.bilibili.com/")
                conn.setRequestProperty("Cache-Control", "no-cache")
                conn.setRequestProperty("Connection", "close")
                if (conn.responseCode == 200) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val json = JSONObject(text)
                    if (json.optInt("code") == 0) {
                        val dataObj = json.optJSONObject("data")
                        val latestArr = JSONArray(prefs.getString("STREAMER_LIST", "[]") ?: "[]")
                        val isMonitoring = prefs.getBoolean("IS_MONITORING", false)
                        val notifiedSet = (prefs.getStringSet("NOTIFIED_UIDS", emptySet()) ?: emptySet()).toMutableSet()
                        val liveUidsNow = mutableSetOf<String>()
                        val newlyLiveNames = mutableListOf<String>()
                        var firstLive: JSONObject? = null
                        for (i in 0 until latestArr.length()) {
                            val item = latestArr.getJSONObject(i)
                            val uid = item.optString("uid")
                            val info = dataObj?.optJSONObject(uid)
                            if (info != null) {
                                val liveStatus = info.optInt("live_status", 0)
                                val liveTime = info.optString("live_time", "0").trim()
                                val sessionKey = "${uid}_${liveTime}"
                                val uname = info.optString("uname", item.optString("uname"))
                                val title = info.optString("title", "")
                                val face = info.optString("face", item.optString("face"))
                                val roomId = info.optString("room_id", item.optString("room_id"))
                                item.put("uname", uname)
                                item.put("live_status", liveStatus)
                                item.put("title", title)
                                item.put("room_id", roomId)
                                if (face.isNotEmpty()) item.put("face", face)
                                if (liveStatus == 1) {
                                    liveUidsNow.add(uid)
                                    if (isMonitoring && !notifiedSet.contains(sessionKey)) {
                                        notifiedSet.removeAll { it.startsWith("${uid}_") || it == uid }
                                        notifiedSet.add(sessionKey)
                                        newlyLiveNames.add(uname)
                                        if (firstLive == null) firstLive = item
                                    }
                                } else {
                                    notifiedSet.removeAll { it.startsWith("${uid}_") || it == uid }
                                }
                            } else {
                                item.put("live_status", 0)
                                notifiedSet.removeAll { it.startsWith("${uid}_") || it == uid }
                            }
                        }
                        if (prefs.getBoolean("IS_RINGING", false)) {
                            val ringingUid = prefs.getString("RINGING_UID", "") ?: ""
                            if (ringingUid.isNotEmpty() && ringingUid != "TEST_UID" && !liveUidsNow.contains(ringingUid)) {
                                LiveMonitorService.stopAlarmImmediately(context)
                            }
                        }
                        prefs.edit()
                            .putString("STREAMER_LIST", latestArr.toString())
                            .putStringSet("NOTIFIED_UIDS", notifiedSet)
                            .commit()
                        context.sendBroadcast(Intent("com.example.bililivealarm.ACTION_STATUS_UPDATED").setPackage(context.packageName))
                        if (firstLive != null && isMonitoring) {
                            triggerAlarm(
                                context,
                                newlyLiveNames.joinToString("、"),
                                firstLive.optString("title", "正在直播中"),
                                firstLive.optString("face", ""),
                                firstLive.optString("uid", ""),
                                firstLive.optString("room_id", "")
                            )
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        private fun wakeScreen(context: Context) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive) {
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK or
                                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                                PowerManager.ON_AFTER_RELEASE,
                        "BiliLiveAlarm::FallbackScreenWake"
                    )
                    wl.acquire(10000L)
                }
            } catch (e: Exception) {}
        }
        private fun sendFallbackAlarmNotification(context: Context, uname: String, title: String, uid: String, roomId: String, face: String) {
            val prefs = context.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
            val alertMode = prefs.getInt("ALERT_MODE", 0)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = when (alertMode) {
                1 -> "BILI_ALARM_LOOP_VIB_CH"
                2 -> "BILI_ALARM_LOOP_SOUND_ONLY_CH"
                else -> "BILI_ALARM_LOOP_ALL_CH"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    channelId,
                    when (alertMode) {
                        1 -> "开播震动提醒"
                        2 -> "开播响铃提醒"
                        else -> "开播响铃震动提醒"
                    },
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
            val watchIntent = Intent(context, AlarmPopupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("AUTO_OPEN_LIVE", true)
                putExtra("UID", uid)
                putExtra("ROOM_ID", roomId)
            }
            val watchPi = PendingIntent.getActivity(
                context, 24, watchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val muteIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.example.bililivealarm.ACTION_MUTE_FROM_NOTIF"
            }
            val mutePi = PendingIntent.getBroadcast(
                context, 23, muteIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle("$uname 开播啦！")
                .setContentText("直播间：$title")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(watchPi)
                .setDeleteIntent(mutePi)
                .addAction(android.R.drawable.ic_media_play, "去看直播", watchPi)
                .addAction(android.R.drawable.ic_lock_silent_mode, "我知道了", mutePi)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreenPi, true)
                .setAutoCancel(true)
                .build()
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
            val notifId = LiveMonitorService.nextAlertNotifId(context)
            nm.notify(notifId, notification)
        }
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.example.bililivealarm.ACTION_MUTE_FROM_NOTIF") {
            LiveMonitorService.stopAlarmImmediately(context)
            return
        }
        val prefs = context.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_MONITORING", false)) return
        val interval = prefs.getInt("POLL_INTERVAL_SECONDS", 20).coerceAtLeast(5)
        scheduleNextAlarm(context, interval)
        if (!isChecking.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BiliLiveAlarm::AlarmWake")
        try {
            wl.acquire(9000L)
        } catch (e: Exception) {}
        val pendingResult = goAsync()
        networkExecutor.execute {
            try {
                checkStreamersSync(appCtx)
            } finally {
                isChecking.set(false)
                try {
                    if (wl.isHeld) wl.release()
                } catch (e: Exception) {}
                pendingResult.finish()
            }
        }
    }
}