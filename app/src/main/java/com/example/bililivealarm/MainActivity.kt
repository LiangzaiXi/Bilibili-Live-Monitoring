package com.example.bililivealarm
import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
class MainActivity : AppCompatActivity() {
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private val streamerDataList = mutableListOf<JSONObject>()
    private val dropdownCandidates = mutableListOf<JSONObject>()
    private lateinit var streamerAdapter: StreamerListAdapter
    private lateinit var dropdownAdapter: DropdownSearchAdapter
    private lateinit var listPopupWindow: ListPopupWindow
    private lateinit var llRingingBanner: LinearLayout
    private lateinit var ivBannerAvatar: ImageView
    private lateinit var tvBannerTitle: TextView
    private lateinit var tvBannerSub: TextView
    private lateinit var tvStatus: TextView
    private var isInternalTextChange = false
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadSavedStreamers()
            updateRingingBannerUI()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestAllRequiredPermissionsOnStart()
        val etSearchInput = findViewById<EditText>(R.id.etSearchInput)
        val btnTestPopup = findViewById<Button>(R.id.btnTestPopup)
        val rgAlertMode = findViewById<RadioGroup>(R.id.rgAlertMode)
        val rbSoundAndVibrate = findViewById<RadioButton>(R.id.rbSoundAndVibrate)
        val rbVibrateOnly = findViewById<RadioButton>(R.id.rbVibrateOnly)
        val rbSoundOnly = findViewById<RadioButton>(R.id.rbSoundOnly)
        val btnStartMonitor = findViewById<Button>(R.id.btnStartMonitor)
        val btnRefreshStatus = findViewById<Button>(R.id.btnRefreshStatus)
        val btnStopMonitor = findViewById<Button>(R.id.btnStopMonitor)
        tvStatus = findViewById(R.id.tvStatus)
        val lvStreamers = findViewById<ListView>(R.id.lvStreamers)
        llRingingBanner = findViewById(R.id.llRingingBanner)
        ivBannerAvatar = findViewById(R.id.ivBannerAvatar)
        tvBannerTitle = findViewById(R.id.tvBannerTitle)
        tvBannerSub = findViewById(R.id.tvBannerSub)
        val btnBannerDismiss = findViewById<Button>(R.id.btnBannerDismiss)
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        btnBannerDismiss.setOnClickListener {
            muteCurrentAlarm()
        }
        llRingingBanner.setOnClickListener {
            val uid = prefs.getString("RINGING_UID", "") ?: ""
            val roomId = prefs.getString("RINGING_ROOM_ID", "") ?: ""
            muteCurrentAlarm()
            openBiliLiveOrSpace(uid, roomId)
        }
        btnTestPopup.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限，否则切到其他App时会被系统拦截弹窗", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return@setOnClickListener
            }
            Toast.makeText(this, "3秒后将触发弹窗，现在可以切到桌面或其他App测试！", Toast.LENGTH_LONG).show()
            val testIntent = Intent(this, LiveMonitorService::class.java).apply {
                action = "ACTION_TEST_POPUP"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(testIntent)
            } else {
                startService(testIntent)
            }
        }
        when (prefs.getInt("ALERT_MODE", 0)) {
            1 -> rbVibrateOnly.isChecked = true
            2 -> rbSoundOnly.isChecked = true
            else -> rbSoundAndVibrate.isChecked = true
        }
        rgAlertMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbVibrateOnly -> 1
                R.id.rbSoundOnly -> 2
                else -> 0
            }
            prefs.edit().putInt("ALERT_MODE", mode).apply()
            if (prefs.getBoolean("IS_RINGING", false)) {
                val applyIntent = Intent(this, LiveMonitorService::class.java).apply {
                    action = "ACTION_APPLY_ALERT_MODE"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(applyIntent)
                } else {
                    startService(applyIntent)
                }
            }
        }
        streamerAdapter = StreamerListAdapter()
        lvStreamers.adapter = streamerAdapter
        dropdownAdapter = DropdownSearchAdapter()
        listPopupWindow = ListPopupWindow(this).apply {
            anchorView = etSearchInput
            setAdapter(dropdownAdapter)
            isModal = false
            setOnItemClickListener { _, _, position, _ ->
                if (position in dropdownCandidates.indices) {
                    val chosen = dropdownCandidates[position]
                    addStreamer(
                        chosen.optString("uid"),
                        chosen.optString("uname"),
                        chosen.optString("face")
                    )
                    isInternalTextChange = true
                    etSearchInput.setText("")
                    isInternalTextChange = false
                    dismiss()
                    hideKeyboard(etSearchInput)
                }
            }
        }
        etSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isInternalTextChange) return
                val keyword = s?.toString()?.trim() ?: ""
                searchRunnable?.let { mainHandler.removeCallbacks(it) }
                if (keyword.isEmpty()) {
                    listPopupWindow.dismiss()
                    return
                }
                val task = Runnable { searchStreamerForDropdown(keyword) }
                searchRunnable = task
                mainHandler.postDelayed(task, 260)
            }
        })
        loadSavedStreamers()
        updateRingingBannerUI()
        refreshLiveStatusImmediately()
        if (prefs.getBoolean("IS_MONITORING", false)) {
            tvStatus.text = "当前状态：监控运行中"
        }
        lvStreamers.setOnItemClickListener { _, _, position, _ ->
            val target = streamerDataList[position]
            val uname = target.optString("uname")
            val uid = target.optString("uid")
            val roomId = target.optString("room_id")
            val options = arrayOf("去B站看直播 / 个人空间", "从监控列表中移除 $uname")
            AlertDialog.Builder(this)
                .setTitle(uname)
                .setItems(options) { _, which ->
                    if (which == 0) {
                        openBiliLiveOrSpace(uid, roomId)
                    } else if (which == 1) {
                        removeStreamer(position, uid)
                    }
                }
                .show()
        }
        btnStartMonitor.setOnClickListener {
            if (streamerDataList.isEmpty()) {
                Toast.makeText(this, "请先在上方搜索框输入并点选添加主播", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putBoolean("IS_MONITORING", true)
                .putStringSet("NOTIFIED_UIDS", emptySet())
                .apply()
            refreshLiveStatusImmediately()
            tvStatus.text = "当前状态：监控运行中"
            Toast.makeText(this, "已启动监控", Toast.LENGTH_SHORT).show()
        }
        btnRefreshStatus.setOnClickListener {
            prefs.edit().putStringSet("NOTIFIED_UIDS", emptySet()).apply()
            Toast.makeText(this, "已重置提醒状态，正在重新检测在播主播...", Toast.LENGTH_SHORT).show()
            refreshLiveStatusImmediately()
        }
        btnStopMonitor.setOnClickListener {
            prefs.edit()
                .putBoolean("IS_MONITORING", false)
                .putBoolean("IS_RINGING", false)
                .apply()
            stopService(Intent(this, LiveMonitorService::class.java))
            updateRingingBannerUI()
            tvStatus.text = "当前状态：已停止监控"
        }
    }
    private fun muteCurrentAlarm() {
        LiveMonitorService.stopAlarmImmediately(this)
        updateRingingBannerUI()
    }
    private fun removeStreamer(position: Int, uid: String) {
        if (position !in streamerDataList.indices) return
        streamerDataList.removeAt(position)
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        val notifiedSet = (prefs.getStringSet("NOTIFIED_UIDS", emptySet()) ?: emptySet()).toMutableSet()
        notifiedSet.remove(uid)
        prefs.edit().putStringSet("NOTIFIED_UIDS", notifiedSet).apply()
        saveStreamers()
        if (streamerDataList.isEmpty()) {
            prefs.edit()
                .putBoolean("IS_MONITORING", false)
                .putBoolean("IS_RINGING", false)
                .apply()
            stopService(Intent(this, LiveMonitorService::class.java))
            tvStatus.text = "当前状态：列表已空，自动停止监控"
        } else {
            val removeIntent = Intent(this, LiveMonitorService::class.java).apply {
                action = "ACTION_REMOVE_UID"
                putExtra("UID", uid)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(removeIntent)
            } else {
                startService(removeIntent)
            }
        }
        streamerAdapter.notifyDataSetChanged()
        updateRingingBannerUI()
    }
    private fun openBiliLiveOrSpace(uid: String, roomId: String) {
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
    private fun updateRingingBannerUI() {
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        val isRinging = prefs.getBoolean("IS_RINGING", false)
        if (isRinging) {
            val uname = prefs.getString("RINGING_UNAME", "主播") ?: "主播"
            val title = prefs.getString("RINGING_TITLE", "正在直播") ?: "正在直播"
            val face = prefs.getString("RINGING_FACE", "") ?: ""
            tvBannerTitle.text = "🔔 $uname 开播啦！"
            tvBannerSub.text = "点击此处看直播 · $title"
            AvatarLoader.load(face, ivBannerAvatar)
            llRingingBanner.visibility = View.VISIBLE
        } else {
            llRingingBanner.visibility = View.GONE
        }
    }
    private fun hideKeyboard(view: View) {
        view.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.example.bililivealarm.ACTION_STATUS_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        loadSavedStreamers()
        updateRingingBannerUI()
    }
    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun refreshLiveStatusImmediately() {
        if (streamerDataList.isEmpty()) return
        val serviceIntent = Intent(this, LiveMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
    private fun searchStreamerForDropdown(keyword: String) {
        networkExecutor.execute {
            val results = mutableListOf<JSONObject>()
            if (keyword.all { it.isDigit() } && keyword.length >= 2) {
                try {
                    val uidUrl = URL("https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids?uids[]=$keyword")
                    val conn = uidUrl.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 4000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (conn.responseCode == 200) {
                        val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val userObj = JSONObject(text).optJSONObject("data")?.optJSONObject(keyword)
                        if (userObj != null) {
                            results.add(JSONObject().apply {
                                put("uid", keyword)
                                put("uname", userObj.optString("uname", "UID:$keyword"))
                                put("face", userObj.optString("face", ""))
                                put("fans", -1)
                            })
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                val searchUrl = URL("https://api.bilibili.com/x/web-interface/search/type?search_type=bili_user&order=fans&keyword=$encoded")
                val conn = searchUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                conn.setRequestProperty("Cookie", "buvid3=12345678-1234-1234-1234-123456789012infoc")
                if (conn.responseCode == 200) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val resultArr = JSONObject(text).optJSONObject("data")?.optJSONArray("result")
                    val userList = mutableListOf<JSONObject>()
                    if (resultArr != null) {
                        for (i in 0 until resultArr.length()) {
                            val obj = resultArr.getJSONObject(i)
                            val mid = obj.optString("mid")
                            val uname = obj.optString("uname")
                            val upic = obj.optString("upic")
                            val fans = obj.optInt("fans", 0)
                            if (mid.isNotEmpty() && results.none { it.optString("uid") == mid }) {
                                userList.add(JSONObject().apply {
                                    put("uid", mid)
                                    put("uname", uname)
                                    put("face", upic)
                                    put("fans", fans)
                                })
                            }
                        }
                    }
                    userList.sortByDescending { it.optInt("fans", 0) }
                    results.addAll(userList.take(12))
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            runOnUiThread {
                dropdownCandidates.clear()
                dropdownCandidates.addAll(results)
                dropdownAdapter.notifyDataSetChanged()
                if (dropdownCandidates.isNotEmpty() && !isFinishing) {
                    listPopupWindow.height = (resources.displayMetrics.density * 280).toInt()
                    listPopupWindow.show()
                } else {
                    listPopupWindow.dismiss()
                }
            }
        }
    }
    private fun addStreamer(uid: String, uname: String, face: String) {
        if (streamerDataList.any { it.optString("uid") == uid }) {
            Toast.makeText(this, "$uname 已在监控列表中", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        val notifiedSet = (prefs.getStringSet("NOTIFIED_UIDS", emptySet()) ?: emptySet()).toMutableSet()
        notifiedSet.remove(uid)
        prefs.edit().putStringSet("NOTIFIED_UIDS", notifiedSet).apply()
        val obj = JSONObject().apply {
            put("uid", uid)
            put("uname", uname)
            put("face", face)
            put("live_status", 0)
            put("title", "")
            put("room_id", "")
        }
        streamerDataList.add(obj)
        saveStreamers()
        streamerAdapter.notifyDataSetChanged()
        refreshLiveStatusImmediately()
    }
    private fun loadSavedStreamers() {
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("STREAMER_LIST", "[]"))
        streamerDataList.clear()
        for (i in 0 until arr.length()) {
            streamerDataList.add(arr.getJSONObject(i))
        }
        streamerDataList.sortByDescending { it.optInt("live_status", 0) }
        streamerAdapter.notifyDataSetChanged()
    }
    private fun saveStreamers() {
        val arr = JSONArray()
        for (item in streamerDataList) {
            arr.put(item)
        }
        getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("STREAMER_LIST", arr.toString())
            .apply()
    }
    private fun formatFansCount(fans: Int): String {
        if (fans < 0) return "UID精准匹配"
        if (fans >= 10000) return String.format("粉丝: %.1f万", fans / 10000f)
        return "粉丝: $fans"
    }
    inner class DropdownSearchAdapter : BaseAdapter() {
        override fun getCount(): Int = dropdownCandidates.size
        override fun getItem(position: Int): Any = dropdownCandidates[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val dp = resources.displayMetrics.density
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                val avatar = ImageView(this@MainActivity).apply {
                    id = 1001
                    layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())
                }
                val textBox = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (10 * dp).toInt()
                    }
                    addView(TextView(this@MainActivity).apply {
                        id = 1002
                        textSize = 15f
                        setTextColor(Color.BLACK)
                    })
                    addView(TextView(this@MainActivity).apply {
                        id = 1003
                        textSize = 12f
                        setTextColor(Color.GRAY)
                    })
                }
                addView(avatar)
                addView(textBox)
            }
            val item = dropdownCandidates[position]
            val ivAvatar = row.findViewById<ImageView>(1001)
            val tvName = row.findViewById<TextView>(1002)
            val tvSub = row.findViewById<TextView>(1003)
            tvName.text = item.optString("uname")
            tvSub.text = "${formatFansCount(item.optInt("fans", 0))}  ·  UID: ${item.optString("uid")}"
            AvatarLoader.load(item.optString("face"), ivAvatar)
            return row
        }
    }
    inner class StreamerListAdapter : BaseAdapter() {
        override fun getCount(): Int = streamerDataList.size
        override fun getItem(position: Int): Any = streamerDataList[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val dp = resources.displayMetrics.density
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                setBackgroundColor(Color.WHITE)
                val avatar = ImageView(this@MainActivity).apply {
                    id = 2001
                    layoutParams = LinearLayout.LayoutParams((50 * dp).toInt(), (50 * dp).toInt())
                }
                val textBox = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (12 * dp).toInt()
                    }
                    addView(TextView(this@MainActivity).apply {
                        id = 2002
                        textSize = 16f
                        setTextColor(Color.BLACK)
                    })
                    addView(TextView(this@MainActivity).apply {
                        id = 2003
                        textSize = 13f
                    })
                }
                addView(avatar)
                addView(textBox)
            }
            val item = streamerDataList[position]
            val ivAvatar = row.findViewById<ImageView>(2001)
            val tvName = row.findViewById<TextView>(2002)
            val tvLive = row.findViewById<TextView>(2003)
            val uname = item.optString("uname")
            val uid = item.optString("uid")
            val liveStatus = item.optInt("live_status", 0)
            val title = item.optString("title", "")
            tvName.text = "$uname  (UID: $uid)"
            if (liveStatus == 1) {
                tvLive.text = if (title.isNotEmpty()) "🔴 直播中：$title" else "🔴 直播中"
                tvLive.setTextColor(Color.parseColor("#00A854"))
            } else {
                tvLive.text = "⚪ 未开播"
                tvLive.setTextColor(Color.parseColor("#888888"))
            }
            AvatarLoader.load(item.optString("face"), ivAvatar)
            return row
        }
    }
    private fun requestAllRequiredPermissionsOnStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("开启后才能在看其他App或桌面时直接弹出带主播头像的开播提醒窗口。")
                    .setPositiveButton("去开启") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                            startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    .setNegativeButton("暂不", null)
                    .show()
            }
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val prefs = getSharedPreferences("BiliPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("IS_MONITORING", false)) {
            finishAndRemoveTask()
        } else {
            super.onBackPressed()
        }
    }
}