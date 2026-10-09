package com.cortex.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var pendingVoiceCommand: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cortex_prefs", MODE_PRIVATE)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setGeolocationEnabled(true)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // let the page use the mic (Web Speech API inside the WebView) when in foreground
                    runOnUiThread { request.grant(request.resources) }
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                }
            }
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            loadUrl("file:///android_asset/www/index.html")
        }
        setContentView(webView)

        requestRuntimePermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val command = intent?.getStringExtra("voice_command")
        if (command != null) {
            pendingVoiceCommand = command
            dispatchWakeEventToWeb(command)
        }
    }

    /** Re-sends the pending wake event once the page is ready, in case it fired before load finished. */
    private fun dispatchWakeEventToWeb(command: String) {
        val escaped = command.replace("\\", "\\\\").replace("'", "\\'")
        val js = "window.dispatchEvent(new CustomEvent('cortex-wake', { detail: { command: '$escaped' } }));"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (prefs.getBoolean("wake_enabled", false) && hasMicPermission()) {
            launchWakeService()
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun launchWakeService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
        } catch (e: Exception) {
        }
    }

    /** Asks (once each) for "display over other apps" and "ignore battery optimization". */
    private fun askBackgroundPermissions() {
        try {
            if (!Settings.canDrawOverlays(this) && !prefs.getBoolean("asked_overlay", false)) {
                prefs.edit().putBoolean("asked_overlay", true).apply()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                return
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("asked_battery", false)) {
                prefs.edit().putBoolean("asked_battery", true).apply()
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        } catch (e: Exception) {
        }
    }

    /** Exposed to the web app's JS so Settings' wake-word toggle can control the native background listener. */
    inner class AndroidBridge {
        @android.webkit.JavascriptInterface
        fun setConfig(license: String?, provider: String?) {
            prefs.edit()
                .putString("license", license ?: "")
                .putString("provider", provider ?: "gemini")
                .apply()
        }

        @android.webkit.JavascriptInterface
        fun startWakeService() {
            prefs.edit().putBoolean("wake_enabled", true).apply()
            runOnUiThread {
                if (!hasMicPermission()) {
                    requestRuntimePermissions()
                } else {
                    launchWakeService()
                    askBackgroundPermissions()
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun stopWakeService() {
            prefs.edit().putBoolean("wake_enabled", false).apply()
            stopService(Intent(this@MainActivity, WakeWordService::class.java))
        }

        @android.webkit.JavascriptInterface
        fun isNativeApp(): Boolean = true
    }
}
