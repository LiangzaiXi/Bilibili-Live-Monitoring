package com.example.bililivealarm
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
class LiveMonitorService : Service() {
    companion object {
        @Volatile
        private var instance: LiveMonitorService? = null
        private val activePlayers = mutableListOf<MediaPlayer>()
        @Synchronized
        fun stopAlarmImmediately(context: Context) {
            for (player in activePlayers) {
                try {
                    if (player.isPlaying) player.stop()
                } catch (e: Exception) {}
                try {
                    player.release()
                } catch (e: Exception) {}
            }
            activePlayers.clear()
            try {
                val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vib.cancel()
            } catch (e: Exception) {}
            val prefs = context.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("IS_RINGING", false).commit()
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(2002)
            } catch (e: Exception) {}
            instance?.let { svc ->
                svc.currentlyRingingUids.clear()
                svc.removeSystemOverlayPopup()
            }
            context.sendBroadcast(Intent("com.example.bililivealarm.ACTION_ALARM_STOPPED").setPackage(context.packageName))
            context.sendBroadcast(Intent("com.example.bililivealarm.ACTION_STATUS_UPDATED").setPackage(context.packageName))
        }
    }
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val isCheckingNetwork = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val currentlyRingingUids = mutableSetOf<String>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    private fun getPollIntervalSeconds(): Int {
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        return prefs.getInt("POLL_INTERVAL_SECONDS", 20).coerceAtLeast(5)
    }
    private fun wakeUpScreenForAlert() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val screenLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "BiliLiveAlarm::ScreenWakeup"
                )
                screenLock.acquire(10000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannels()
        val interval = getPollIntervalSeconds()
        when (intent?.action) {
            "ACTION_MUTE_ALARM" -> {
                stopAlarmImmediately(this)
                startForegroundCompat(buildSilentServiceNotification("正在监控主播开播状态，每${interval}秒检测"))
                scheduleNextHardwareAlarm(interval)
                return START_STICKY
            }
            "ACTION_WATCH_LIVE" -> {
                val uid = intent.getStringExtra("UID") ?: ""
                val roomId = intent.getStringExtra("ROOM_ID") ?: ""
                stopAlarmImmediately(this)
                openBiliLiveRoom(uid, roomId)
                startForegroundCompat(buildSilentServiceNotification("正在监控主播开播状态，每${interval}秒检测"))
                scheduleNextHardwareAlarm(interval)
                return START_STICKY
            }
            "ACTION_APPLY_ALERT_MODE" -> {
                val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("IS_RINGING", false)) {
                    startSoundAndVibrationByMode()
                }
                return START_STICKY
            }
            "ACTION_TRIGGER_FROM_RECEIVER" -> {
                val uname = intent.getStringExtra("UNAME") ?: "关注的主播"
                val title = intent.getStringExtra("TITLE") ?: "正在直播中"
                val face = intent.getStringExtra("FACE") ?: ""
                val uid = intent.getStringExtra("UID") ?: ""
                val roomId = intent.getStringExtra("ROOM_ID") ?: ""
                if (uid.isNotEmpty()) currentlyRingingUids.add(uid)
                startForegroundCompat(buildSilentServiceNotification("正在监控主播开播状态，每${interval}秒检测"))
                scheduleNextHardwareAlarm(interval)
                triggerAlarmAndPopup(uname, title, face, uid, roomId)
                return START_STICKY
            }
            "ACTION_TEST_POPUP" -> {
                startForegroundCompat(buildSilentServiceNotification("正在执行弹窗测试"))
                mainHandler.postDelayed({
                    val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
                    val arr = JSONArray(prefs.getString("STREAMER_LIST", "[]") ?: "[]")
                    val firstObj = if (arr.length() > 0) arr.getJSONObject(0) else null
                    val testName = firstObj?.optString("uname", "测试主播") ?: "测试主播"
                    val testTitle = firstObj?.optString("title")?.takeIf { it.isNotEmpty() } ?: "开播弹窗测试"
                    val testFace = firstObj?.optString("face", "") ?: ""
                    val testUid = firstObj?.optString("uid", "") ?: ""
                    val testRoomId = firstObj?.optString("room_id", "") ?: ""
                    currentlyRingingUids.add("TEST_UID")
                    triggerAlarmAndPopup(testName, testTitle, testFace, testUid, testRoomId)
                }, 3000)
                return START_STICKY
            }
            "ACTION_REMOVE_UID" -> {
                val removedUid = intent.getStringExtra("UID") ?: ""
                currentlyRingingUids.remove(removedUid)
                val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
                val arr = JSONArray(prefs.getString("STREAMER_LIST", "[]") ?: "[]")
                if (currentlyRingingUids.isEmpty() || arr.length() == 0) {
                    stopAlarmImmediately(this)
                }
                if (arr.length() == 0 && !prefs.getBoolean("IS_MONITORING", false)) {
                    cancelHardwareAlarm()
                    stopSelf()
                }
                return START_STICKY
            }
        }
        startForegroundCompat(buildSilentServiceNotification("正在监控主播开播状态，每${interval}秒检测"))
        scheduleNextHardwareAlarm(interval)
        triggerNetworkCheck()
        return START_STICKY
    }
    private fun triggerNetworkCheck() {
        if (isCheckingNetwork.compareAndSet(false, true)) {
            networkExecutor.execute {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BiliLiveAlarm::CheckLock")
                try {
                    wl.acquire(12000L)
                    checkAllStreamersStatus()
                } finally {
                    try {
                        if (wl.isHeld) wl.release()
                    } catch (e: Exception) {}
                    isCheckingNetwork.set(false)
                }
            }
        }
    }
    private fun openBiliLiveRoom(uid: String, roomId: String) {
        try {
            val uri = when {
                roomId.isNotEmpty() && roomId != "0" -> Uri.parse("bilibili://live/$roomId")
                uid.isNotEmpty() -> Uri.parse("bilibili://space/$uid")
                else -> Uri.parse("bilibili://")
            }
            val jumpIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(jumpIntent)
        } catch (e: Exception) {
            try {
                val webUrl = when {
                    roomId.isNotEmpty() && roomId != "0" -> "https://live.bilibili.com/$roomId"
                    uid.isNotEmpty() -> "https://space.bilibili.com/$uid"
                    else -> "https://live.bilibili.com"
                }
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            startForeground(1, notification)
        }
    }
    private fun scheduleNextHardwareAlarm(delaySeconds: Int) {
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_MONITORING", false)) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = "com.example.bililivealarm.ACTION_ALARM_POLL"
        }
        val pi = PendingIntent.getBroadcast(
            this, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerTime = System.currentTimeMillis() + delaySeconds * 1000L
        try {
            val showIntent = Intent(this, MainActivity::class.java)
            val showPi = PendingIntent.getActivity(
                this, 1002, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val clockInfo = AlarmManager.AlarmClockInfo(triggerTime, showPi)
            alarmManager.setAlarmClock(clockInfo, pi)
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi)
        }
    }
    private fun cancelHardwareAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = "com.example.bililivealarm.ACTION_ALARM_POLL"
        }
        val pi = PendingIntent.getBroadcast(
            this, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi)
    }
    private fun checkAllStreamersStatus() {
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        val snapshotArr = JSONArray(prefs.getString("STREAMER_LIST", "[]") ?: "[]")
        if (snapshotArr.length() == 0) {
            stopAlarmImmediately(this)
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
        queryBuilder.append("_t=").append(SystemClock.elapsedRealtime())
        try {
            val url = URL(queryBuilder.toString())
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.useCaches = false
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (conn.responseCode == 200) {
                val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(text)
                if (json.optInt("code") == 0) {
                    val dataObj = json.optJSONObject("data")
                    val latestArr = JSONArray(prefs.getString("STREAMER_LIST", "[]") ?: "[]")
                    val isMonitoring = prefs.getBoolean("IS_MONITORING", false)
                    val notifiedSet = (prefs.getStringSet("NOTIFIED_UIDS", emptySet()) ?: emptySet()).toMutableSet()
                    var firstNewlyLive: JSONObject? = null
                    val newlyLiveNames = mutableListOf<String>()
                    val newlyLiveUids = mutableListOf<String>()
                    val activeUidsInList = mutableSetOf<String>()
                    for (i in 0 until latestArr.length()) {
                        val item = latestArr.getJSONObject(i)
                        val uid = item.optString("uid")
                        activeUidsInList.add(uid)
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
                            if (face.isNotEmpty()) {
                                item.put("face", face)
                            }
                            if (liveStatus == 1) {
                                if (isMonitoring && !notifiedSet.contains(sessionKey)) {
                                    notifiedSet.removeAll { it.startsWith("${uid}_") || it == uid }
                                    notifiedSet.add(sessionKey)
                                    newlyLiveNames.add(uname)
                                    newlyLiveUids.add(uid)
                                    if (firstNewlyLive == null) {
                                        firstNewlyLive = item
                                    }
                                }
                            } else {
                                notifiedSet.removeAll { it.startsWith("${uid}_") || it == uid }
                                currentlyRingingUids.remove(uid)
                            }
                        } else {
                            item.put("live_status", 0)
                            notifiedSet.removeAll { it.startsWith("${uid}_") || it == uid }
                            currentlyRingingUids.remove(uid)
                        }
                    }
                    if (!currentlyRingingUids.contains("TEST_UID")) {
                        currentlyRingingUids.retainAll(activeUidsInList)
                        if (currentlyRingingUids.isEmpty() && prefs.getBoolean("IS_RINGING", false)) {
                            stopAlarmImmediately(this)
                        }
                    }
                    prefs.edit()
                        .putString("STREAMER_LIST", latestArr.toString())
                        .putStringSet("NOTIFIED_UIDS", notifiedSet)
                        .commit()
                    sendBroadcast(Intent("com.example.bililivealarm.ACTION_STATUS_UPDATED").setPackage(packageName))
                    if (firstNewlyLive != null && isMonitoring) {
                        currentlyRingingUids.addAll(newlyLiveUids)
                        val allNames = newlyLiveNames.joinToString("、")
                        triggerAlarmAndPopup(
                            allNames,
                            firstNewlyLive.optString("title", "正在直播中"),
                            firstNewlyLive.optString("face", ""),
                            firstNewlyLive.optString("uid", ""),
                            firstNewlyLive.optString("room_id", "")
                        )
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun triggerAlarmAndPopup(uname: String, title: String, face: String, uid: String, roomId: String) {
        wakeUpScreenForAlert()
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("IS_RINGING", true)
            .putString("RINGING_UNAME", uname)
            .putString("RINGING_TITLE", title)
            .putString("RINGING_FACE", face)
            .putString("RINGING_UID", uid)
            .putString("RINGING_ROOM_ID", roomId)
            .commit()
        sendBroadcast(Intent("com.example.bililivealarm.ACTION_STATUS_UPDATED").setPackage(packageName))
        val popupIntent = Intent(this, AlarmPopupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("UNAME", uname)
            putExtra("TITLE", title)
            putExtra("FACE", face)
            putExtra("UID", uid)
            putExtra("ROOM_ID", roomId)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(2002)
        manager.notify(2002, buildHighPriorityAlertNotification(uname, title, uid, roomId, popupIntent))
        mainHandler.post {
            val overlayShown = showSystemOverlayPopup(uname, title, face, uid, roomId)
            if (!overlayShown) {
                try {
                    startActivity(popupIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        startSoundAndVibrationByMode()
    }
    private fun showSystemOverlayPopup(uname: String, title: String, face: String, uid: String, roomId: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return false
        }
        removeSystemOverlayPopupImmediate()
        return try {
            val themedCtx = ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light)
            val dp = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val cardWidth = (screenWidth * 0.85f).toInt()
            val cardBg = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 18 * dp
                setStroke((2 * dp).toInt(), Color.parseColor("#FB7299"))
            }
            val card = LinearLayout(themedCtx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = cardBg
                setPadding((22 * dp).toInt(), (22 * dp).toInt(), (22 * dp).toInt(), (22 * dp).toInt())
            }
            val tvHeader = TextView(themedCtx).apply {
                text = "靓仔监控：主播开播啦！"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#FB7299"))
                gravity = Gravity.CENTER
            }
            val ivAvatar = ImageView(themedCtx).apply {
                val size = (80 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    topMargin = (14 * dp).toInt()
                }
            }
            val tvName = TextView(themedCtx).apply {
                text = "$uname 开播了！"
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * dp).toInt()
                }
            }
            val tvTitle = TextView(themedCtx).apply {
                text = "直播间：$title"
                textSize = 14f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (6 * dp).toInt()
                }
            }
            val btnRow = LinearLayout(themedCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (18 * dp).toInt()
                }
            }
            val watchBtnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#FB7299"))
                cornerRadius = 10 * dp
            }
            val tvWatchBtn = TextView(themedCtx).apply {
                text = "去看直播"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = watchBtnBg
                setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (6 * dp).toInt()
                }
                setOnClickListener {
                    stopAlarmImmediately(this@LiveMonitorService)
                    openBiliLiveRoom(uid, roomId)
                }
            }
            val dismissBtnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#E0E0E0"))
                cornerRadius = 10 * dp
            }
            val tvDismissBtn = TextView(themedCtx).apply {
                text = "我知道了"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#333333"))
                gravity = Gravity.CENTER
                background = dismissBtnBg
                setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (6 * dp).toInt()
                }
                setOnClickListener {
                    stopAlarmImmediately(this@LiveMonitorService)
                }
            }
            btnRow.addView(tvWatchBtn)
            btnRow.addView(tvDismissBtn)
            card.addView(tvHeader)
            card.addView(ivAvatar)
            card.addView(tvName)
            card.addView(tvTitle)
            card.addView(btnRow)
            AvatarLoader.load(face, ivAvatar)
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                cardWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                dimAmount = 0.55f
            }
            windowManager?.addView(card, params)
            overlayView = card
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    private fun removeSystemOverlayPopupImmediate() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayView = null
    }
    private fun removeSystemOverlayPopup() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeSystemOverlayPopupImmediate()
        } else {
            mainHandler.post {
                removeSystemOverlayPopupImmediate()
            }
        }
    }
    @Synchronized
    private fun startSoundAndVibrationByMode() {
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        val alertMode = prefs.getInt("ALERT_MODE", 0)
        for (player in activePlayers) {
            try {
                if (player.isPlaying) player.stop()
            } catch (e: Exception) {}
            try {
                player.release()
            } catch (e: Exception) {}
        }
        activePlayers.clear()
        try {
            vibrator?.cancel()
        } catch (e: Exception) {}
        val alarmAudioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (alertMode == 0 || alertMode == 2) {
            try {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val newPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, alarmUri)
                    setAudioAttributes(alarmAudioAttrs)
                    isLooping = true
                    prepare()
                    start()
                }
                activePlayers.add(newPlayer)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (alertMode == 0 || alertMode == 1) {
            try {
                val pattern = longArrayOf(0, 1000, 500, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(pattern, 0)
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(effect, alarmAudioAttrs)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val serviceChannel = NotificationChannel(
                "BILI_SERVICE_SILENT_CH",
                "后台监控状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            val alertChannel = NotificationChannel(
                "BILI_ALARM_POPUP_HIGH_CH",
                "主播开播强提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }
    private fun buildSilentServiceNotification(content: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPi = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "BILI_SERVICE_SILENT_CH")
            .setContentTitle("靓仔监控")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(mainPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    private fun buildHighPriorityAlertNotification(uname: String, title: String, uid: String, roomId: String, fullScreenPopupIntent: Intent): Notification {
        val watchIntent = Intent(this, LiveMonitorService::class.java).apply {
            action = "ACTION_WATCH_LIVE"
            putExtra("UID", uid)
            putExtra("ROOM_ID", roomId)
        }
        val watchPi = PendingIntent.getService(
            this, 13, watchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val muteIntent = Intent(this, LiveMonitorService::class.java).apply {
            action = "ACTION_MUTE_ALARM"
        }
        val mutePi = PendingIntent.getService(
            this, 11, muteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val fullScreenPi = PendingIntent.getActivity(
            this, 12, fullScreenPopupIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "BILI_ALARM_POPUP_HIGH_CH")
            .setContentTitle("$uname 开播啦！")
            .setContentText("直播间：$title")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(watchPi)
            .addAction(android.R.drawable.ic_media_play, "去看直播", watchPi)
            .addAction(android.R.drawable.ic_lock_silent_mode, "我知道了", mutePi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("IS_MONITORING", false)) {
            scheduleNextHardwareAlarm(5)
        }
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("IS_MONITORING", false)) {
            scheduleNextHardwareAlarm(5)
        } else {
            cancelHardwareAlarm()
        }
        stopAlarmImmediately(this)
        if (instance === this) instance = null
        super.onDestroy()
    }
}