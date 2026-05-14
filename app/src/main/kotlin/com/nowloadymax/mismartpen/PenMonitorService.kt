/**
 * MiSmartPenX
 * Designed and Developed by NowLoadY
 */
package com.nowloadymax.mismartpen

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.input.InputManager
import android.os.*
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.RemoteViews

class PenMonitorService : Service(), InputManager.InputDeviceListener {

    companion object {
        private const val CHANNEL_ID = "PenMonitorService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE = "com.nowloadymax.mismartpen.TOGGLE"
        private const val ACTION_PLUS = "com.nowloadymax.mismartpen.PLUS"
        private const val ACTION_MINUS = "com.nowloadymax.mismartpen.MINUS"
        private const val ACTION_REFRESH = "REFRESH_CONFIG"
        private const val MIUI_PEN_ACTION = "miui.intent.action.ACTION_PEN_REVERSE_CHARGE_STATE"

        private const val MODE_AUTO = 0
        private const val MODE_FORCE_SHOW = 1
        private const val MODE_FORCE_HIDE = 2
    }

    private lateinit var windowManager: WindowManager
    private lateinit var inputManager: InputManager
    private lateinit var prefs: SharedPreferences
    
    private var floatingView: View? = null
    private var displayMode = MODE_AUTO
    private var threshold = 20
    private var uiAlpha = 220
    private var uiColorNormal = "#1A1A1A"
    
    private var currentSoc: Int = 0
    private var currentDecimal: Int = 0
    private var isCharging: Boolean = false
    private var isPenConnected = false
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MIUI_PEN_ACTION) {
                currentSoc = intent.getIntExtra("miui.intent.extra.REVERSE_PEN_SOC", currentSoc)
                currentDecimal = intent.getIntExtra("miui.intent.extra.soc_decimal", 0)
                isCharging = (intent.getIntExtra("miui.intent.extra.ACTION_PEN_REVERSE_CHARGE_STATE", -1) != 2)
                updateLogic()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inputManager = getSystemService(INPUT_SERVICE) as InputManager
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        
        applyConfig(null) // 初始加载
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        
        inputManager.registerInputDeviceListener(this, null)
        registerReceiver(batteryReceiver, IntentFilter(MIUI_PEN_ACTION))
        
        checkPenConnection()
        refreshFromSticky()
    }

    private fun applyConfig(intent: Intent?) {
        // 优先从 Intent 拿实时值（用于 UI 同步），拿不到则从磁盘读取
        threshold = intent?.getIntExtra("threshold", prefs.getInt("threshold", 20)) ?: prefs.getInt("threshold", 20)
        
        val alphaInput = intent?.getIntExtra("alpha", -1).takeIf { it != -1 } ?: prefs.getInt("alpha", 85)
        uiAlpha = (alphaInput * 2.55).toInt().coerceIn(0, 255)
        
        uiColorNormal = intent?.getStringExtra("color_normal") ?: prefs.getString("color_normal", "#1A1A1A") ?: "#1A1A1A"
        
        if (intent?.action == ACTION_REFRESH) displayMode = MODE_AUTO
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            handleIntentAction(intent)
        } else {
            refreshFromSticky()
        }
        return START_STICKY
    }

    private fun handleIntentAction(intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                displayMode = if (floatingView != null) MODE_FORCE_HIDE else MODE_FORCE_SHOW
                prefs.edit().putInt("display_mode", displayMode).apply()
            }
            ACTION_PLUS -> {
                threshold = (threshold + 5).coerceAtMost(100)
                prefs.edit().putInt("threshold", threshold).apply()
                displayMode = MODE_AUTO
            }
            ACTION_MINUS -> {
                threshold = (threshold - 5).coerceAtLeast(0)
                prefs.edit().putInt("threshold", threshold).apply()
                displayMode = MODE_AUTO
            }
            ACTION_REFRESH -> applyConfig(intent)
        }
        updateLogic()
    }

    private fun refreshFromSticky() {
        val sticky = registerReceiver(null, IntentFilter(MIUI_PEN_ACTION))
        sticky?.let {
            currentSoc = it.getIntExtra("miui.intent.extra.REVERSE_PEN_SOC", currentSoc)
            currentDecimal = it.getIntExtra("miui.intent.extra.soc_decimal", 0)
            isCharging = (it.getIntExtra("miui.intent.extra.ACTION_PEN_REVERSE_CHARGE_STATE", -1) != 2)
            updateLogic()
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = checkPenConnection()
    override fun onInputDeviceRemoved(deviceId: Int) = checkPenConnection()
    override fun onInputDeviceChanged(deviceId: Int) = checkPenConnection()

    private fun checkPenConnection() {
        isPenConnected = inputManager.inputDeviceIds.any { 
            inputManager.getInputDevice(it)?.name?.contains("Xiaomi Smart Pen", true) == true 
        }
        updateLogic()
    }

    private fun updateLogic() {
        val shouldShow = isPenConnected && when (displayMode) {
            MODE_FORCE_SHOW -> true
            MODE_FORCE_HIDE -> false
            else -> isCharging || currentSoc <= threshold
        }
        if (shouldShow) showOverlay() else hideOverlay()
        updateUI()
        updateNotification()
    }

    private fun showOverlay() {
        if (floatingView == null) {
            floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_pen, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 12
            }
            windowManager.addView(floatingView, params)
        }
    }

    private fun hideOverlay() {
        floatingView?.let { windowManager.removeView(it); floatingView = null }
    }

    private fun updateUI() {
        floatingView?.let { view ->
            view.findViewById<TextView>(R.id.pen_text).text = "$currentSoc.$currentDecimal%"
            val baseColor = if (isCharging) Color.parseColor("#22CC22") else Color.parseColor(uiColorNormal)
            val finalColor = (uiAlpha shl 24) or (baseColor and 0x00FFFFFF)
            view.findViewById<LinearLayout>(R.id.pen_card).background.setTint(finalColor)
            view.findViewById<android.widget.ImageView>(R.id.charge_icon).visibility = if (isCharging) View.VISIBLE else View.GONE
        }
    }

    private fun updateNotification() {
        val remoteViews = RemoteViews(packageName, R.layout.layout_notification).apply {
            setTextViewText(R.id.noti_threshold_text, "自动显示阈值: $threshold%")
            setTextViewText(R.id.noti_btn_toggle, if (floatingView != null) "隐藏" else "显示")
            setOnClickPendingIntent(R.id.noti_btn_toggle, getActionPI(ACTION_TOGGLE, 0))
            setOnClickPendingIntent(R.id.noti_btn_plus, getActionPI(ACTION_PLUS, 1))
            setOnClickPendingIntent(R.id.noti_btn_minus, getActionPI(ACTION_MINUS, 2))
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCustomContentView(remoteViews)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun getActionPI(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, PenMonitorService::class.java).apply { this.action = action },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "MiSmartPenX Service", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        inputManager.unregisterInputDeviceListener(this)
        unregisterReceiver(batteryReceiver)
    }
}
