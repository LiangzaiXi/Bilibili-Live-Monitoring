package com.example.bililivealarm
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
class AlarmPopupActivity : AppCompatActivity() {
    private lateinit var ivAvatar: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvTitle: TextView
    private var currentUid = ""
    private var currentRoomId = ""
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        val filter = IntentFilter("com.example.bililivealarm.ACTION_ALARM_STOPPED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, filter)
        }
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            setBackgroundColor(Color.WHITE)
        }
        val tvHeader = TextView(this).apply {
            text = "靓仔监控：主播开播啦！"
            textSize = 18f
            setTextColor(Color.parseColor("#FB7299"))
            gravity = Gravity.CENTER
        }
        ivAvatar = ImageView(this).apply {
            val size = (80 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                topMargin = (14 * dp).toInt()
            }
        }
        tvName = TextView(this).apply {
            textSize = 19f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * dp).toInt()
            }
        }
        tvTitle = TextView(this).apply {
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
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (18 * dp).toInt()
            }
        }
        val btnWatch = Button(this).apply {
            text = "去看直播"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FB7299"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * dp).toInt()
            }
            setOnClickListener {
                LiveMonitorService.stopAlarmImmediately(this@AlarmPopupActivity)
                openBiliLive(currentUid, currentRoomId)
                finish()
            }
        }
        val btnDismiss = Button(this).apply {
            text = "我知道了"
            textSize = 15f
            setTextColor(Color.parseColor("#333333"))
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (6 * dp).toInt()
            }
            setOnClickListener {
                LiveMonitorService.stopAlarmImmediately(this@AlarmPopupActivity)
                finish()
            }
        }
        btnRow.addView(btnWatch)
        btnRow.addView(btnDismiss)
        root.addView(tvHeader)
        root.addView(ivAvatar)
        root.addView(tvName)
        root.addView(tvTitle)
        root.addView(btnRow)
        setContentView(root)
        setFinishOnTouchOutside(false)
        updateUI(intent)
    }
    private fun openBiliLive(uid: String, roomId: String) {
        try {
            val uri = when {
                roomId.isNotEmpty() && roomId != "0" -> Uri.parse("bilibili://live/$roomId")
                uid.isNotEmpty() -> Uri.parse("bilibili://space/$uid")
                else -> Uri.parse("bilibili://")
            }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            val webUrl = when {
                roomId.isNotEmpty() && roomId != "0" -> "https://live.bilibili.com/$roomId"
                uid.isNotEmpty() -> "https://space.bilibili.com/$uid"
                else -> "https://live.bilibili.com"
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        updateUI(intent)
    }
    private fun updateUI(intent: Intent) {
        val uname = intent.getStringExtra("UNAME") ?: "关注的主播"
        val title = intent.getStringExtra("TITLE") ?: "正在直播中"
        val face = intent.getStringExtra("FACE") ?: ""
        currentUid = intent.getStringExtra("UID") ?: ""
        currentRoomId = intent.getStringExtra("ROOM_ID") ?: ""
        tvName.text = "$uname 开播了！"
        tvTitle.text = "直播间：$title"
        AvatarLoader.load(face, ivAvatar)
    }
    override fun onDestroy() {
        try {
            unregisterReceiver(closeReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}