/**
 * MiSmartPenX
 * Designed and Developed by NowLoadY
 */
package com.nowloadymax.mismartpen

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.*
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.RemoteViews
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class PenMonitorService : Service(), InputManager.InputDeviceListener {

    companion object {
        private const val CHANNEL_ID = "PenMonitorService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE = "com.nowloadymax.mismartpen.TOGGLE"
        private const val ACTION_PLUS = "com.nowloadymax.mismartpen.PLUS"
        private const val ACTION_MINUS = "com.nowloadymax.mismartpen.MINUS"
        private const val ACTION_REFRESH = "REFRESH_CONFIG"
        private const val MIUI_PEN_ACTION = "miui.intent.action.ACTION_PEN_REVERSE_CHARGE_STATE"
        private const val BLUETOOTH_BATTERY_POLL_MS = 60_000L
        private const val BATTERY_SOURCE_TOLERANCE = 5
        private const val TAG = "MiSmartPenX"
        private const val STYLUS_DRAG_START_DP = 18
        private const val VISUALIZER_SIZE_DP = 156
        private const val VISUALIZER_RETURN_DELAY_MS = 600L
        private const val VISUALIZER_RETURN_ANIMATION_MS = 260L
        private const val MENU_GAP_DP = 8
        private const val MENU_ITEM_HEIGHT_DP = 34
        private const val PREF_OVERLAY_X = "overlay_x"
        private const val PREF_OVERLAY_Y = "overlay_y"
        private const val PREF_OVERLAY_POSITION_SET = "overlay_position_set"

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
    private var overlayParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var visualizerView: PenVisualizerView? = null
    private var visualizerParams: WindowManager.LayoutParams? = null
    private var visualizerActive = false
    private var visualizerReturning = false
    private var visualizerReturnAnimator: ValueAnimator? = null
    private var stylusDownRawX = 0f
    private var stylusDownRawY = 0f
    private var fingerDownRawX = 0f
    private var fingerDownRawY = 0f
    private var fingerDownOverlayX = 0
    private var fingerDownOverlayY = 0
    private var fingerDraggingOverlay = false
    private val returnToBatteryRunnable = Runnable { exitVisualizerMode(animated = true) }
    
    private var currentSoc: Int = 0
    private var currentDecimal: Int = 0
    private var accurateSoc: Int? = null
    private var accurateDecimal: Int = 0
    private var bluetoothSoc: Int? = null
    private var isCharging: Boolean = false
    private var isPenConnected = false
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothBatteryPoller = object : Runnable {
        override fun run() {
            refreshBluetoothBattery()
            handler.postDelayed(this, BLUETOOTH_BATTERY_POLL_MS)
        }
    }

    private val overlayTouchListener = View.OnTouchListener { _, event ->
        when {
            event.isStylusEvent() -> handleStylusOverlayTouch(event)
            event.isFingerEvent() -> handleFingerOverlayTouch(event)
            else -> false
        }
    }

    private fun handleStylusOverlayTouch(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(returnToBatteryRunnable)
                hideOverlayMenu()
                stylusDownRawX = event.rawX
                stylusDownRawY = event.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - stylusDownRawX
                val dy = event.rawY - stylusDownRawY
                val distance = sqrt(dx * dx + dy * dy)
                if (!visualizerActive && distance > stylusDragStartPx()) {
                    enterVisualizerMode(event)
                }
                if (visualizerActive) updateVisualizer(event, touching = true)
                true
            }
            MotionEvent.ACTION_UP -> {
                if (visualizerActive) {
                    updateVisualizer(event, touching = false)
                    scheduleVisualizerReturn()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                exitVisualizerMode()
                true
            }
            else -> visualizerActive
        }
    }

    private fun handleFingerOverlayTouch(event: MotionEvent): Boolean {
        val view = floatingView ?: return false
        val params = overlayParams ?: return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ensureOverlayUsesAbsolutePosition(view, params)
                fingerDownRawX = event.rawX
                fingerDownRawY = event.rawY
                fingerDownOverlayX = params.x
                fingerDownOverlayY = params.y
                fingerDraggingOverlay = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - fingerDownRawX
                val dy = event.rawY - fingerDownRawY
                if (!fingerDraggingOverlay && sqrt(dx * dx + dy * dy) > touchSlopPx()) {
                    fingerDraggingOverlay = true
                    hideOverlayMenu()
                }
                if (fingerDraggingOverlay) {
                    params.x = clampToScreenX(fingerDownOverlayX + dx.toInt(), view.width)
                    params.y = clampToScreenY(fingerDownOverlayY + dy.toInt(), view.height)
                    windowManager.updateViewLayout(view, params)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (fingerDraggingOverlay) {
                    saveOverlayPosition(params)
                } else {
                    toggleOverlayMenu()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                fingerDraggingOverlay = false
                true
            }
            else -> true
        }
    }

    private val visualizerTouchListener = View.OnTouchListener { _, event ->
        if (!event.isStylusEvent()) return@OnTouchListener false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                handler.removeCallbacks(returnToBatteryRunnable)
                updateVisualizer(event, touching = true)
                true
            }
            MotionEvent.ACTION_UP -> {
                updateVisualizer(event, touching = false)
                scheduleVisualizerReturn()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                exitVisualizerMode()
                true
            }
            else -> true
        }
    }

    private val visualizerHoverListener = View.OnHoverListener { _, event ->
        if (!event.isStylusEvent()) return@OnHoverListener false

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                handler.removeCallbacks(returnToBatteryRunnable)
                updateVisualizer(event, touching = false)
                true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                exitVisualizerMode(animated = true)
                true
            }
            else -> false
        }
    }
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MIUI_PEN_ACTION) {
                isCharging = (intent.getIntExtra("miui.intent.extra.ACTION_PEN_REVERSE_CHARGE_STATE", -1) != 2)
                val soc = intent.getIntExtra("miui.intent.extra.REVERSE_PEN_SOC", -1)
                if (soc in 0..100) {
                    accurateSoc = soc
                    accurateDecimal = intent.getIntExtra("miui.intent.extra.soc_decimal", 0).coerceIn(0, 9)
                    Log.d(TAG, "MIUI accurate battery: $accurateSoc.$accurateDecimal%, charging=$isCharging")
                }
                selectDisplayBattery()
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
        refreshBluetoothBattery()
        handler.postDelayed(bluetoothBatteryPoller, BLUETOOTH_BATTERY_POLL_MS)
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
            ACTION_TOGGLE -> toggleDisplayMode()
            ACTION_PLUS -> adjustThreshold(5)
            ACTION_MINUS -> adjustThreshold(-5)
            ACTION_REFRESH -> applyConfig(intent)
        }
        updateLogic()
    }

    private fun toggleDisplayMode() {
        hideOverlayMenu()
        displayMode = if (floatingView != null) MODE_FORCE_HIDE else MODE_FORCE_SHOW
        prefs.edit().putInt("display_mode", displayMode).apply()
    }

    private fun adjustThreshold(delta: Int) {
        threshold = (threshold + delta).coerceIn(0, 100)
        prefs.edit().putInt("threshold", threshold).apply()
        displayMode = MODE_AUTO
        refreshOverlayMenu()
    }

    private fun refreshFromSticky() {
        val sticky = registerReceiver(null, IntentFilter(MIUI_PEN_ACTION))
        sticky?.let {
            isCharging = (it.getIntExtra("miui.intent.extra.ACTION_PEN_REVERSE_CHARGE_STATE", -1) != 2)
            val soc = it.getIntExtra("miui.intent.extra.REVERSE_PEN_SOC", -1)
            if (soc in 0..100) {
                accurateSoc = soc
                accurateDecimal = it.getIntExtra("miui.intent.extra.soc_decimal", 0).coerceIn(0, 9)
                Log.d(TAG, "MIUI sticky battery: $accurateSoc.$accurateDecimal%, charging=$isCharging")
            }
            selectDisplayBattery()
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
        Log.d(TAG, "Input pen connected: $isPenConnected")
        if (isPenConnected) refreshBluetoothBattery()
        updateLogic()
    }

    private fun refreshBluetoothBattery() {
        val batteryLevel = getXiaomiPenBluetoothBatteryLevel()
        if (batteryLevel != null) {
            bluetoothSoc = batteryLevel
            Log.d(TAG, "Bluetooth battery accepted: $bluetoothSoc%")
            selectDisplayBattery()
            updateLogic()
        } else {
            Log.d(TAG, "Bluetooth battery unavailable")
        }
    }

    private fun getXiaomiPenBluetoothBatteryLevel(): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val bluetoothManager = getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = bluetoothManager.adapter ?: return null
        return try {
            adapter.bondedDevices
                ?.asSequence()
                ?.filter { it.isXiaomiSmartPen() }
                ?.mapNotNull { it.getBatteryLevelCompat() }
                ?.firstOrNull { it in 0..100 }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun BluetoothDevice.getBatteryLevelCompat(): Int? {
        return try {
            val value = javaClass.getMethod("getBatteryLevel").invoke(this) as? Int
            Log.d(TAG, "Bluetooth device battery: name=$name address=$address level=$value")
            value?.takeIf { it in 0..100 }
        } catch (_: ReflectiveOperationException) {
            Log.d(TAG, "Bluetooth battery reflection unavailable for $address")
            null
        } catch (_: SecurityException) {
            Log.d(TAG, "Bluetooth battery denied for $address")
            null
        }
    }

    private fun BluetoothDevice.isXiaomiSmartPen(): Boolean {
        val deviceName = try {
            name
        } catch (_: SecurityException) {
            null
        } ?: return false

        return deviceName.contains("Xiaomi Smart Pen", true) ||
            deviceName.contains("Mi Smart Pen", true) ||
            deviceName.contains("Smart Pen", true)
    }

    private fun selectDisplayBattery() {
        val bluetooth = bluetoothSoc
        val accurate = accurateSoc
        when {
            bluetooth == null && accurate != null -> {
                currentSoc = accurate
                currentDecimal = accurateDecimal
                Log.d(TAG, "Display battery selected: accurate=$currentSoc.$currentDecimal%")
            }
            // Treat the MIUI side-attach value as calibration unless Bluetooth is much lower,
            // which usually means the attached reading is stale and Bluetooth has newer drain data.
            bluetooth != null && accurate != null &&
                bluetooth >= accurate - BATTERY_SOURCE_TOLERANCE -> {
                currentSoc = accurate
                currentDecimal = accurateDecimal
                Log.d(TAG, "Display battery selected: accurate=$currentSoc.$currentDecimal%, bluetooth=$bluetooth%")
            }
            bluetooth != null -> {
                currentSoc = bluetooth
                currentDecimal = 0
                Log.d(TAG, "Display battery selected: bluetooth=$currentSoc%")
            }
        }
    }

    private fun updateLogic() {
        val shouldShow = isPenConnected && when (displayMode) {
            MODE_FORCE_SHOW -> true
            MODE_FORCE_HIDE -> false
            else -> isCharging || currentSoc <= threshold
        }
        if (visualizerActive) {
            updateUI()
            updateNotification()
            return
        }
        if (shouldShow) showOverlay() else hideOverlay()
        updateUI()
        updateNotification()
    }

    private fun showOverlay() {
        if (floatingView == null) {
            floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_pen, null)
            floatingView?.setOnTouchListener(overlayTouchListener)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                if (prefs.getBoolean(PREF_OVERLAY_POSITION_SET, false)) {
                    gravity = Gravity.TOP or Gravity.START
                    x = prefs.getInt(PREF_OVERLAY_X, 0)
                    y = prefs.getInt(PREF_OVERLAY_Y, 12)
                } else {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 12
                }
            }
            overlayParams = params
            windowManager.addView(floatingView, params)
        }
    }

    private fun hideOverlay() {
        hideOverlayMenu()
        exitVisualizerMode(animated = false)
        floatingView?.let { windowManager.removeView(it); floatingView = null }
        overlayParams = null
    }

    private fun enterVisualizerMode(event: MotionEvent) {
        val size = visualizerSizePx()
        val view = PenVisualizerView(this).apply {
            setOnTouchListener(visualizerTouchListener)
            setOnHoverListener(visualizerHoverListener)
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        visualizerActive = true
        visualizerReturning = false
        visualizerReturnAnimator?.cancel()
        visualizerView = view
        visualizerParams = params
        floatingView?.alpha = 0f
        windowManager.addView(view, params)
        updateVisualizer(event, touching = true)
    }

    private fun updateVisualizer(event: MotionEvent, touching: Boolean) {
        val view = visualizerView ?: return
        val params = visualizerParams ?: return
        if (visualizerReturning) return
        val size = visualizerSizePx()
        params.x = (event.rawX - size / 2f).toInt()
        params.y = (event.rawY - size / 2f).toInt()
        windowManager.updateViewLayout(view, params)
        view.updateFrom(event, touching)
    }

    private fun scheduleVisualizerReturn() {
        handler.removeCallbacks(returnToBatteryRunnable)
        handler.postDelayed(returnToBatteryRunnable, VISUALIZER_RETURN_DELAY_MS)
    }

    private fun exitVisualizerMode(animated: Boolean = false) {
        handler.removeCallbacks(returnToBatteryRunnable)
        if (!animated) {
            removeVisualizerView()
            floatingView?.alpha = 1f
            updateUI()
            return
        }

        val view = visualizerView ?: run {
            floatingView?.alpha = 1f
            return
        }
        val params = visualizerParams ?: run {
            removeVisualizerView()
            floatingView?.alpha = 1f
            return
        }
        if (visualizerReturning) return

        visualizerActive = false
        visualizerReturning = true
        floatingView?.alpha = 0f
        floatingView?.animate()
            ?.alpha(1f)
            ?.setDuration(VISUALIZER_RETURN_ANIMATION_MS)
            ?.start()

        val startX = params.x
        val startY = params.y
        val target = getBatteryOverlayCenterTarget(visualizerSizePx())
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = VISUALIZER_RETURN_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                params.x = (startX + (target.first - startX) * fraction).toInt()
                params.y = (startY + (target.second - startY) * fraction).toInt()
                view.alpha = 1f - 0.75f * fraction
                val scale = 1f - 0.65f * fraction
                view.scaleX = scale
                view.scaleY = scale
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (visualizerReturnAnimator === animation) {
                        visualizerReturnAnimator = null
                    }
                    view.alpha = 0f
                    view.visibility = View.INVISIBLE
                    removeVisualizerView()
                    floatingView?.alpha = 1f
                    updateUI()
                }
            })
        }
        visualizerReturnAnimator = animator
        animator.start()
    }

    private fun removeVisualizerView() {
        val animator = visualizerReturnAnimator
        visualizerReturnAnimator = null
        animator?.cancel()
        visualizerView?.let {
            it.animate().cancel()
            runCatching { windowManager.removeView(it) }
        }
        visualizerView = null
        visualizerParams = null
        visualizerActive = false
        visualizerReturning = false
    }

    private fun getBatteryOverlayCenterTarget(visualizerSize: Int): Pair<Int, Int> {
        val view = floatingView ?: return Pair(0, 12)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0] + view.width / 2 - visualizerSize / 2
        val y = location[1] + view.height / 2 - visualizerSize / 2
        return Pair(x, y)
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

    private fun toggleOverlayMenu() {
        if (menuView == null) {
            showOverlayMenu()
        } else {
            hideOverlayMenu()
        }
    }

    private fun showOverlayMenu() {
        val anchor = floatingView ?: return
        val anchorParams = overlayParams ?: return
        hideOverlayMenu()
        ensureOverlayUsesAbsolutePosition(anchor, anchorParams)

        val menu = createOverlayMenuView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorParams.x
            y = anchorParams.y + anchor.height + dp(MENU_GAP_DP)
        }
        menuView = menu
        menuParams = params
        windowManager.addView(menu, params)
    }

    private fun refreshOverlayMenu() {
        val existingParams = menuParams ?: return
        val old = menuView ?: return
        runCatching { windowManager.removeView(old) }
        val menu = createOverlayMenuView()
        menuView = menu
        windowManager.addView(menu, existingParams)
    }

    private fun hideOverlayMenu() {
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
        menuParams = null
    }

    private fun createOverlayMenuView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(0xE61A1A1A.toInt(), dp(18).toFloat())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            elevation = dp(6).toFloat()

            addView(menuButton(if (floatingView != null) "隐藏" else "显示") {
                toggleDisplayMode()
                updateLogic()
            })
            addView(menuDivider())
            addView(menuButton("−") {
                adjustThreshold(-5)
                updateLogic()
            })
            addView(menuLabel())
            addView(menuButton("+") {
                adjustThreshold(5)
                updateLogic()
            })
        }
    }

    private fun menuButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = dp(38)
            height = dp(MENU_ITEM_HEIGHT_DP)
            background = roundedBackground(0x22FFFFFF, dp(14).toFloat())
            setOnClickListener { onClick() }
        }
    }

    private fun menuLabel(): TextView {
        return TextView(this).apply {
            text = "$threshold%"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = dp(48)
            height = dp(MENU_ITEM_HEIGHT_DP)
        }
    }

    private fun menuDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply { setColor(0x33FFFFFF) }
        }
    }

    private fun updateNotification() {
        val notification = createForegroundNotification()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun createForegroundNotification(): Notification {
        val remoteViews = RemoteViews(packageName, R.layout.layout_notification).apply {
            setTextViewText(R.id.noti_threshold_text, "自动显示阈值: $threshold%")
            setTextViewText(R.id.noti_btn_toggle, if (floatingView != null) "隐藏" else "显示")
            setOnClickPendingIntent(R.id.noti_btn_toggle, getActionPI(ACTION_TOGGLE, 0))
            setOnClickPendingIntent(R.id.noti_btn_plus, getActionPI(ACTION_PLUS, 1))
            setOnClickPendingIntent(R.id.noti_btn_minus, getActionPI(ACTION_MINUS, 2))
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCustomContentView(remoteViews)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .build()
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
        handler.removeCallbacks(bluetoothBatteryPoller)
        handler.removeCallbacks(returnToBatteryRunnable)
        hideOverlay()
        inputManager.unregisterInputDeviceListener(this)
        unregisterReceiver(batteryReceiver)
    }

    private fun MotionEvent.isStylusEvent(): Boolean {
        return pointerCount > 0 && (
            getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
            )
    }

    private fun MotionEvent.isFingerEvent(): Boolean {
        return pointerCount > 0 && getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
    }

    private fun ensureOverlayUsesAbsolutePosition(view: View, params: WindowManager.LayoutParams) {
        if ((params.gravity and Gravity.START) == Gravity.START && (params.gravity and Gravity.TOP) == Gravity.TOP) return

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        params.gravity = Gravity.TOP or Gravity.START
        params.x = location[0]
        params.y = location[1]
        windowManager.updateViewLayout(view, params)
    }

    private fun saveOverlayPosition(params: WindowManager.LayoutParams) {
        prefs.edit()
            .putBoolean(PREF_OVERLAY_POSITION_SET, true)
            .putInt(PREF_OVERLAY_X, params.x)
            .putInt(PREF_OVERLAY_Y, params.y)
            .apply()
    }

    private fun clampToScreenX(x: Int, viewWidth: Int): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        return x.coerceIn(0, (screenWidth - viewWidth).coerceAtLeast(0))
    }

    private fun clampToScreenY(y: Int, viewHeight: Int): Int {
        val screenHeight = resources.displayMetrics.heightPixels
        return y.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
    }

    private fun stylusDragStartPx(): Float {
        return STYLUS_DRAG_START_DP * resources.displayMetrics.density
    }

    private fun visualizerSizePx(): Int {
        return (VISUALIZER_SIZE_DP * resources.displayMetrics.density).toInt()
    }

    private fun touchSlopPx(): Float {
        return ViewConfiguration.get(this).scaledTouchSlop.toFloat()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }
}

private class PenVisualizerView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD91A1A1A.toInt()
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
    }
    private val pressurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA22CC88.toInt()
        style = Paint.Style.FILL
    }
    private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * context.resources.displayMetrics.density
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var pressure = 0f
    private var tilt = 0f
    private var orientation = 0f
    private var touching = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun updateFrom(event: MotionEvent, touching: Boolean) {
        pressure = event.pressure.coerceIn(0f, 1f)
        tilt = event.getAxisValue(MotionEvent.AXIS_TILT).coerceIn(0f, (PI / 2.0).toFloat())
        orientation = event.orientation
        this.touching = touching
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f
        val innerRadius = radius - ringPaint.strokeWidth

        canvas.drawCircle(cx, cy, innerRadius, fillPaint)
        canvas.drawCircle(cx, cy, innerRadius, ringPaint)

        val pressureRadius = innerRadius * pressure
        canvas.drawCircle(cx, cy, pressureRadius, pressurePaint)

        val tiltNorm = (tilt / (PI / 2.0).toFloat()).coerceIn(0f, 1f)
        val vectorLength = innerRadius * tiltNorm
        val endX = cx + sin(orientation) * vectorLength
        val endY = cy - cos(orientation) * vectorLength
        canvas.drawLine(cx, cy, endX, endY, vectorPaint)

        val centerRadius = if (touching) radius * 0.045f else radius * 0.03f
        canvas.drawCircle(cx, cy, centerRadius, centerPaint)
    }
}
