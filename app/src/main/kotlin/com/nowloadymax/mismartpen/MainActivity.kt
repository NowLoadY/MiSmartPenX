/**
 * MiSmartPenX
 * Designed and Developed by NowLoadY
 */
package com.nowloadymax.mismartpen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var thresholdSlider: Slider
    private lateinit var thresholdLabel: TextView
    private lateinit var alphaSlider: Slider
    private lateinit var alphaLabel: TextView

    // 监听配置变化，实现双向同步
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
        when (key) {
            "threshold" -> {
                val value = sharedPrefs.getInt(key, 20).toFloat()
                if (thresholdSlider.value != value) {
                    thresholdSlider.value = value
                    thresholdLabel.text = "弹出电量阈值: ${value.toInt()}%"
                }
            }
            "alpha" -> {
                val value = sharedPrefs.getInt(key, 85).toFloat()
                if (alphaSlider.value != value) {
                    alphaSlider.value = value
                    alphaLabel.text = "胶囊透明度: ${value.toInt()}%"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        
        thresholdLabel = findViewById(R.id.threshold_label)
        thresholdSlider = findViewById(R.id.threshold_slider)
        alphaLabel = findViewById(R.id.alpha_label)
        alphaSlider = findViewById(R.id.alpha_slider)

        // 初始化数据
        val savedThreshold = prefs.getInt("threshold", 20).toFloat()
        thresholdSlider.value = savedThreshold
        thresholdLabel.text = "弹出电量阈值: ${savedThreshold.toInt()}%"

        val savedAlpha = prefs.getInt("alpha", 85).toFloat()
        alphaSlider.value = savedAlpha
        alphaLabel.text = "胶囊透明度: ${savedAlpha.toInt()}%"
        
        // 设置监听器
        thresholdSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                thresholdLabel.text = "弹出电量阈值: ${value.toInt()}%"
                prefs.edit().putInt("threshold", value.toInt()).commit()
                syncConfigToService()
            }
        }

        alphaSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                alphaLabel.text = "胶囊透明度: ${value.toInt()}%"
                prefs.edit().putInt("alpha", value.toInt()).commit()
                syncConfigToService()
            }
        }

        // Colors
        findViewById<View>(R.id.color_dark).setOnClickListener { updateColor("#1A1A1A") }
        findViewById<View>(R.id.color_green).setOnClickListener { updateColor("#22CC22") }
        findViewById<View>(R.id.color_blue).setOnClickListener { updateColor("#007AFF") }
        findViewById<View>(R.id.color_purple).setOnClickListener { updateColor("#AF52DE") }

        checkPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun updateColor(hex: String) {
        prefs.edit().putString("color_normal", hex).commit()
        syncConfigToService()
    }

    private fun syncConfigToService() {
        val intent = Intent(this, PenMonitorService::class.java).apply { 
            action = "REFRESH_CONFIG"
            putExtra("threshold", prefs.getInt("threshold", 20))
            putExtra("alpha", prefs.getInt("alpha", 85))
            putExtra("color_normal", prefs.getString("color_normal", "#1A1A1A"))
        }
        startForegroundService(intent)
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                return
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 102)
        } else {
            startMonitorService()
        }
    }

    private fun startMonitorService() {
        startForegroundService(Intent(this, PenMonitorService::class.java))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102 && Settings.canDrawOverlays(this)) startMonitorService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) checkPermissionsAndStart()
    }
}
