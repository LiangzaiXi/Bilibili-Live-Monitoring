package com.example.bililivealarm
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
class LiveMonitorService : Service() {
    companion object {
        @Volatile
        private var instance: LiveMonitorService? = null
        private val activePlayers = mutableListOf<MediaPlayer>()
        @Synchronized
        fun nextAlertNotifId(context: Context): Int {
            val prefs = context.getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
            val oldId = prefs.getInt("LAST_ALERT_NOTIF_ID", 2002)
            val newId = if (oldId in 2002..2998) oldId + 1 else 2002
            prefs.edit().putInt("LAST_ALERT_NOTIF_ID", newId).commit()
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(oldId)
            } catch (e: Exception) {}
            return newId
        }
        fun applyAlertModeIfRinging() {
            instance?.startSoundAndVibrationByMode()
        }
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
            val lastAlertId = prefs.getInt("LAST_ALERT_NOTIF_ID", 2002)
            prefs.edit().putBoolean("IS_RINGING", false).commit()
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(lastAlertId)
                nm.cancel(2002)
            } catch (e: Exception) {}
            instance?.stopServiceCleanly()
            context.sendBroadcast(Intent("com.example.bililivealarm.ACTION_ALARM_STOPPED").setPackage(context.packageName))
            context.sendBroadcast(Intent("com.example.bililivealarm.ACTION_STATUS_UPDATED").setPackage(context.packageName))
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannels()
        when (intent?.action) {
            "ACTION_MUTE_ALARM" -> {
                stopAlarmImmediately(this)
                return START_NOT_STICKY
            }
            "ACTION_START_ALARM" -> {
                val uname = intent.getStringExtra("UNAME") ?: "关注的主播"
                val title = intent.getStringExtra("TITLE") ?: "正在直播中"
                val face = intent.getStringExtra("FACE") ?: ""
                val uid = intent.getStringExtra("UID") ?: ""
                val roomId = intent.getStringExtra("ROOM_ID") ?: ""
                triggerAlarmAndPopup(uname, title, face, uid, roomId)
                return START_NOT_STICKY
            }
        }
        stopServiceCleanly()
        return START_NOT_STICKY
    }
    private fun stopServiceCleanly() {
        removeSystemOverlayPopup()
        mainHandler.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Exception) {}
            stopSelf()
        }
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
        val popupIntent = Intent(this, AlarmPopupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("UNAME", uname)
            putExtra("TITLE", title)
            putExtra("FACE", face)
            putExtra("UID", uid)
            putExtra("ROOM_ID", roomId)
        }
        val notifId = nextAlertNotifId(this)
        val alertNotification = buildHighPriorityAlertNotification(uname, title, uid, roomId, popupIntent)
        startForegroundCompat(notifId, alertNotification)
        sendBroadcast(Intent("com.example.bililivealarm.ACTION_STATUS_UPDATED").setPackage(packageName))
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
    private fun startForegroundCompat(id: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(id, notification)
            }
        } catch (e: Exception) {
            startForeground(id, notification)
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
            val alertChannel = NotificationChannel(
                "BILI_ALARM_POPUP_HIGH_CH",
                "主播开播强提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(alertChannel)
        }
    }
    private fun buildHighPriorityAlertNotification(uname: String, title: String, uid: String, roomId: String, fullScreenPopupIntent: Intent): Notification {
        val watchIntent = Intent(this, AlarmPopupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("AUTO_OPEN_LIVE", true)
            putExtra("UID", uid)
            putExtra("ROOM_ID", roomId)
        }
        val watchPi = PendingIntent.getActivity(
            this, 13, watchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val muteIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = "com.example.bililivealarm.ACTION_MUTE_FROM_NOTIF"
        }
        val mutePi = PendingIntent.getBroadcast(
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
            .setDeleteIntent(mutePi)
            .addAction(android.R.drawable.ic_media_play, "去看直播", watchPi)
            .addAction(android.R.drawable.ic_lock_silent_mode, "我知道了", mutePi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setAutoCancel(true)
            .build()
    }
    override fun onDestroy() {
        removeSystemOverlayPopupImmediate()
        if (instance === this) instance = null
        super.onDestroy()
    }
}