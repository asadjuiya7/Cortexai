package com.cortex.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var pendingVoiceCommand: String? = null
    private var printView: WebView? = null

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
        for (perm in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) needed.add(perm)
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
        fun devicePrompt(): String = DeviceControl.PROMPT

        @android.webkit.JavascriptInterface
        fun deviceAction(action: String?, param: String?): String {
            var result = "Done."
            val latch = java.util.concurrent.CountDownLatch(1)
            runOnUiThread {
                try {
                    result = DeviceControl.run(this@MainActivity, action ?: "", param ?: "")
                } catch (e: Exception) {
                    result = "Sorry, that did not work."
                }
                latch.countDown()
            }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            return result
        }

        /** Saves a file made by Cortex into the phone's Downloads folder. */
        @android.webkit.JavascriptInterface
        fun saveFile(name: String?, mime: String?, base64: String?): String {
            val fileName = (name ?: "cortex-file.txt").replace(Regex("[\\\\/:*?\"<>|]"), "_")
            return try {
                val bytes = Base64.decode(base64 ?: "", Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return "I could not save $fileName."
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    "Saved $fileName in your Downloads folder."
                } else {
                    val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val f = java.io.File(dir, fileName)
                    f.writeBytes(bytes)
                    "Saved $fileName in the Cortex folder: ${f.absolutePath}"
                }
            } catch (e: Exception) {
                "I could not save $fileName."
            }
        }

        /** Opens Android's print screen for an HTML page, where the user picks "Save as PDF". */
        @android.webkit.JavascriptInterface
        fun printHtml(title: String?, html: String?) {
            runOnUiThread {
                try {
                    val wv = WebView(this@MainActivity)
                    printView = wv
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                            val jobName = title ?: "Cortex document"
                            pm.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
                        }
                    }
                    wv.loadDataWithBaseURL(null, html ?: "", "text/html", "UTF-8", null)
                } catch (e: Exception) {
                }
            }
        }

        // ---- Schedule screen
        @android.webkit.JavascriptInterface
        fun listTasks(): String = Scheduler.listJson(this@MainActivity)

        @android.webkit.JavascriptInterface
        fun addTask(whenSpec: String?, repeat: String?, action: String?, param: String?, say: String?): String =
            Scheduler.addFromParam(
                this@MainActivity,
                listOf(whenSpec ?: "", repeat ?: "once", action ?: "remind", param ?: "", say ?: "").joinToString("|")
            )

        @android.webkit.JavascriptInterface
        fun cancelTask(id: String?): String = Scheduler.cancelById(this@MainActivity, id?.toLongOrNull() ?: -1L)

        @android.webkit.JavascriptInterface
        fun deletePlanNamed(name: String?): String = Scheduler.deletePlan(this@MainActivity, name ?: "")

        @android.webkit.JavascriptInterface
        fun isNativeApp(): Boolean = true
    }
}
