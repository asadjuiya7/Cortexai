package com.cortex.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    /** Exposed to the web app's JS so Settings' wake-word toggle can control the native background listener. */
    inner class AndroidBridge {
        @android.webkit.JavascriptInterface
        fun startWakeService() {
            prefs.edit().putBoolean("wake_enabled", true).apply()
            val intent = Intent(this@MainActivity, WakeWordService::class.java)
            ContextCompat.startForegroundService(this@MainActivity, intent)
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
