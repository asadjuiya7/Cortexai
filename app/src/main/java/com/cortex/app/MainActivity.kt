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
import android.webkit.ValueCallback
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
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_CODE = 7421

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

                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    return try {
                        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf", "text/*", "application/json", "audio/*"))
                        }
                        startActivityForResult(Intent.createChooser(i, "Choose file"), FILE_CHOOSER_CODE)
                        true
                    } catch (e: Exception) {
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = null
                        false
                    }
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_CODE) {
            val r: Array<Uri>? = if (resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri }
                else data.data?.let { arrayOf(it) }
            } else null
            filePathCallback?.onReceiveValue(r)
            filePathCallback = null
        }
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
        fun setVoiceStyle(on: Boolean, pitch: Float, rate: Float) {
            prefs.edit()
                .putBoolean("voice_style_on", on)
                .putFloat("voice_pitch", pitch.coerceIn(0.5f, 1.6f))
                .putFloat("voice_rate", rate.coerceIn(0.7f, 1.4f))
                .apply()
        }

        // ---- Native voice recorder. The WebView microphone is often blocked or busy, so Settings > My Voice records here.
        @Volatile private var recOn = false
        private var recRec: android.media.AudioRecord? = null
        private var recThread: Thread? = null
        private val recBytes = java.io.ByteArrayOutputStream()
        private var recWakeWasOn = false

        @android.webkit.JavascriptInterface
        fun startRecord(): Boolean {
            if (recOn) return true
            if (!hasMicPermission()) {
                runOnUiThread { requestRuntimePermissions() }
                return false
            }
            recWakeWasOn = prefs.getBoolean("wake_enabled", false)
            if (recWakeWasOn) {
                // The background wake-word listener uses the microphone too: pause it while recording.
                stopService(Intent(this@MainActivity, WakeWordService::class.java))
                try { Thread.sleep(700) } catch (e: Exception) { }
            }
            val rate = 16000
            val min = android.media.AudioRecord.getMinBufferSize(
                rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            if (min <= 0) return false
            val ar = try {
                android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC, rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, min * 4
                )
            } catch (e: Exception) {
                return false
            }
            if (ar.state != android.media.AudioRecord.STATE_INITIALIZED) {
                ar.release()
                return false
            }
            synchronized(recBytes) { recBytes.reset() }
            recRec = ar
            recOn = true
            ar.startRecording()
            recThread = Thread {
                val buf = ByteArray(min)
                while (recOn) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n > 0) {
                        synchronized(recBytes) { recBytes.write(buf, 0, n) }
                    } else if (n < 0) {
                        break
                    }
                }
            }.also { it.start() }
            return true
        }

        /** Stops recording and returns the audio as base64 WAV ("" when nothing usable was recorded). */
        @android.webkit.JavascriptInterface
        fun stopRecord(): String {
            if (!recOn && recRec == null) return ""
            recOn = false
            try { recThread?.join(1500) } catch (e: Exception) { }
            try { recRec?.stop() } catch (e: Exception) { }
            try { recRec?.release() } catch (e: Exception) { }
            recRec = null
            recThread = null
            val pcm = synchronized(recBytes) { recBytes.toByteArray() }
            if (recWakeWasOn) {
                recWakeWasOn = false
                runOnUiThread { launchWakeService() }
            }
            if (pcm.size < 16000) return ""
            return Base64.encodeToString(wavOf(pcm, 16000), Base64.NO_WRAP)
        }

        private fun wavOf(pcm: ByteArray, rate: Int): ByteArray {
            val bb = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            bb.put("data".toByteArray()).putInt(pcm.size).put(pcm)
            return bb.array()
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
