/**
 * MiSmartPenX
 * Designed and Developed by NowLoadY
 */
package com.nowloadymax.mismartpen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        
        // Threshold Slider
        val thresholdLabel = findViewById<TextView>(R.id.threshold_label)
        val thresholdSlider = findViewById<Slider>(R.id.threshold_slider)
        val savedThreshold = prefs.getInt("threshold", 20).toFloat()
        thresholdSlider.value = savedThreshold
        thresholdLabel.text = "弹出电量阈值: ${savedThreshold.toInt()}%"
        
        thresholdSlider.addOnChangeListener { _, value, _ ->
            thresholdLabel.text = "弹出电量阈值: ${value.toInt()}%"
            prefs.edit().putInt("threshold", value.toInt()).apply()
            notifyService()
        }

        // Alpha Slider
        val alphaLabel = findViewById<TextView>(R.id.alpha_label)
        val alphaSlider = findViewById<Slider>(R.id.alpha_slider)
        val savedAlpha = prefs.getInt("alpha", 85).toFloat()
        alphaSlider.value = savedAlpha
        alphaLabel.text = "胶囊透明度: ${savedAlpha.toInt()}%"
        
        alphaSlider.addOnChangeListener { _, value, _ ->
            alphaLabel.text = "胶囊透明度: ${value.toInt()}%"
            prefs.edit().putInt("alpha", value.toInt()).apply()
            notifyService()
        }

        // Colors
        findViewById<View>(R.id.color_dark).setOnClickListener { updateColor("#1A1A1A") }
        findViewById<View>(R.id.color_green).setOnClickListener { updateColor("#22CC22") }
        findViewById<View>(R.id.color_blue).setOnClickListener { updateColor("#007AFF") }
        findViewById<View>(R.id.color_purple).setOnClickListener { updateColor("#AF52DE") }

        checkPermissionsAndStart()
    }

    private fun updateColor(hex: String) {
        getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString("color_normal", hex).apply()
        notifyService()
    }

    private fun notifyService() {
        val intent = Intent(this, PenMonitorService::class.java).apply { action = "REFRESH_CONFIG" }
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
