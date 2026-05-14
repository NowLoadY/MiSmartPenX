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
    private var uiAlpha = 220 // 0-255
    private var uiColorNormal = "#1A1A1A"
    
    private var currentSoc: Int = 0
    private var currentDecimal: Int = 0
    private var isCharging: Boolean = false
    private var isPenConnected = false
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MIUI_PEN_ACTION) {
                val soc = intent.getIntExtra("miui.intent.extra.REVERSE_PEN_SOC", -1)
                if (soc != -1) {
                    currentSoc = soc
                    currentDecimal = intent.getIntExtra("miui.intent.extra.soc_decimal", 0)
                    val state = intent.getIntExtra("miui.intent.extra.ACTION_PEN_REVERSE_CHARGE_STATE", -1)
                    isCharging = (state != 2)
                    updateLogic()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inputManager = getSystemService(INPUT_SERVICE) as InputManager
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        loadConfig()
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        
        inputManager.registerInputDeviceListener(this, null)
        registerReceiver(batteryReceiver, IntentFilter(MIUI_PEN_ACTION))
        
        checkPenConnection()
        refreshFromSticky()
    }

    private fun loadConfig() {
        threshold = prefs.getInt("threshold", 20)
        displayMode = prefs.getInt("display_mode", MODE_AUTO)
        uiAlpha = (prefs.getInt("alpha", 85) * 2.55).toInt().coerceIn(0, 255)
        uiColorNormal = prefs.getString("color_normal", "#1A1A1A") ?: "#1A1A1A"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            handleIntentAction(action)
        } else {
            refreshFromSticky()
        }
        return START_STICKY
    }

    private fun handleIntentAction(action: String) {
        when (action) {
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
            ACTION_REFRESH -> {
                loadConfig()
                displayMode = MODE_AUTO
            }
        }
        updateLogic()
        updateNotification()
    }

    private fun refreshFromSticky() {
        val sticky = registerReceiver(null, IntentFilter(MIUI_PEN_ACTION))
        sticky?.let {
            currentSoc = it.getIntExtra("miui.intent.extra.REVERSE_PEN_SOC", currentSoc)
            currentDecimal = it.getIntExtra("miui.intent.extra.soc_decimal", 0)
            val state = it.getIntExtra("miui.intent.extra.ACTION_PEN_REVERSE_CHARGE_STATE", -1)
            isCharging = (state != 2)
            updateLogic()
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = checkPenConnection()
    override fun onInputDeviceRemoved(deviceId: Int) = checkPenConnection()
    override fun onInputDeviceChanged(deviceId: Int) = checkPenConnection()

    private fun checkPenConnection() {
        isPenConnected = false
        for (id in inputManager.inputDeviceIds) {
            val device = inputManager.getInputDevice(id)
            if (device?.name?.contains("Xiaomi Smart Pen", ignoreCase = true) == true) {
                isPenConnected = true
                break
            }
        }
        updateLogic()
    }

    private fun updateLogic() {
        val autoShow = isCharging || currentSoc <= threshold
        val shouldShow = isPenConnected && when (displayMode) {
            MODE_FORCE_SHOW -> true
            MODE_FORCE_HIDE -> false
            else -> autoShow
        }
        if (shouldShow) showOverlay() else hideOverlay()
        updateUI()
        updateNotification()
    }

    private fun showOverlay() {
        if (floatingView == null) {
            floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_pen, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 12 
            windowManager.addView(floatingView, params)
            updateUI()
        }
    }

    private fun hideOverlay() {
        floatingView?.let {
            windowManager.removeView(it)
            floatingView = null
        }
    }

    private fun updateUI() {
        floatingView?.let { view ->
            val card = view.findViewById<LinearLayout>(R.id.pen_card)
            val text = view.findViewById<TextView>(R.id.pen_text)
            val icon = view.findViewById<TextView>(R.id.pen_icon)
            val chargeIcon = view.findViewById<android.widget.ImageView>(R.id.charge_icon)
            
            text.text = "$currentSoc.$currentDecimal%"
            
            val baseColor = if (isCharging) Color.parseColor("#22CC22") else Color.parseColor(uiColorNormal)
            // 混合透明度: (uiAlpha << 24) | (baseColor & 0x00FFFFFF)
            val finalColor = (uiAlpha shl 24) or (baseColor and 0x00FFFFFF)
            
            card.background.setTint(finalColor)
            icon.setTextColor(if (isCharging) Color.WHITE else Color.parseColor("#B3FFFFFF"))
            chargeIcon.visibility = if (isCharging) View.VISIBLE else View.GONE
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createForegroundNotification())
    }

    private fun createForegroundNotification(): Notification {
        val remoteViews = RemoteViews(packageName, R.layout.layout_notification)
        remoteViews.setTextViewText(R.id.noti_threshold_text, "自动显示阈值: $threshold%")
        val isShowingNow = floatingView != null
        remoteViews.setTextViewText(R.id.noti_btn_toggle, if (isShowingNow) "隐藏" else "显示")

        val pToggle = PendingIntent.getService(this, 0, Intent(this, PenMonitorService::class.java).apply { action = ACTION_TOGGLE }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pPlus = PendingIntent.getService(this, 1, Intent(this, PenMonitorService::class.java).apply { action = ACTION_PLUS }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pMinus = PendingIntent.getService(this, 2, Intent(this, PenMonitorService::class.java).apply { action = ACTION_MINUS }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        remoteViews.setOnClickPendingIntent(R.id.noti_btn_toggle, pToggle)
        remoteViews.setOnClickPendingIntent(R.id.noti_btn_plus, pPlus)
        remoteViews.setOnClickPendingIntent(R.id.noti_btn_minus, pMinus)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCustomContentView(remoteViews)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .build()
    }

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
