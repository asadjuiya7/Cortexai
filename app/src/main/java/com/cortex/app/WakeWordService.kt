package com.cortex.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Always-on "Hey Cortex" listener.
 *
 * WAKE    -> listens (continuously, restarting instantly) for the wake phrase.
 * COMMAND -> popup is open and we listen for what the user wants.
 * BUSY    -> asking the AI / speaking the reply (mic paused so it does not hear itself).
 *
 * After a reply is spoken we go back to COMMAND so the conversation can continue;
 * if the user stays silent the popup closes and we go back to WAKE.
 */
class WakeWordService : Service() {

    private enum class Mode { WAKE, COMMAND, BUSY }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var restartAttempts = 0
    private var stopped = false
    private var mode = Mode.WAKE
    private var lastNotifText = ""
    private var lastLaunchAt = 0L
    private var lastActivityAt = 0L
    private var commandErrors = 0
    private var lastAskAt = 0L
    private var lastAskText = ""
    private var wakeLock: PowerManager.WakeLock? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var overlayRoot: LinearLayout? = null
    private var overlayStatus: TextView? = null
    private var overlayBody: TextView? = null

    private val history = ArrayList<Pair<String, String>>()
    private val restartRunnable = Runnable { beginListening() }
    private val closeRunnable = Runnable { endConversation() }
    private val speakWatchdog = Runnable { afterSpeaking() }

    companion object {
        const val CHANNEL_ID = "cortex_wake_channel"
        const val NOTIF_ID = 42
        const val WORKER_URL = "https://cortexai.asadjuiya7.workers.dev"
        val SYSTEM_PROMPT =
            "You are Cortex, a friendly voice assistant. Your developer and creator is Muhammad Asad Ashraf. " +
                "When asked who made or created you, always answer Muhammad Asad Ashraf. " +
                "Never say you were made by Google, OpenAI or Anthropic and never mention Gemini, GPT or Claude. " +
                "Reply in at most two short sentences, plain text only, no markdown, no lists, no emojis. " +
                "Reply in the language the user speaks; if the user speaks Urdu, reply in Urdu script. " +
                DeviceControl.PROMPT
        const val IDLE_CLOSE_MS = 120000L
        val STOP_PATTERN = Regex("""(?:^|\s)(?:stop|bye|goodbye|cancel|dismiss|close)(?:\s|$)""")
        val WAKE_PATTERN = Regex(
            """\b(?:(?:hey|hi|ok|okay)\s+(?:cortex|cortez|codex|context|cortical)|cortex)\b[,.]?\s*""",
            RegexOption.IGNORE_CASE
        )
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            restartAttempts = 0
            if (mode == Mode.WAKE) updateNotification("Listening for \"Hey Cortex\"")
        }

        override fun onResults(results: Bundle) {
            if (stopped || mode == Mode.BUSY) return
            val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (mode == Mode.COMMAND) {
                val said = matches?.firstOrNull()?.trim() ?: ""
                commandErrors = 0
                if (said.isEmpty()) {
                    scheduleRestart(50)
                } else {
                    handleCommandText(said)
                }
                return
            }
            // WAKE mode
            if (matches != null) {
                for (transcript in matches) {
                    val m = WAKE_PATTERN.find(transcript) ?: continue
                    val remainder = transcript.substring(m.range.last + 1).trim()
                    onWake(remainder)
                    return
                }
            }
            scheduleRestart(50)
        }

        override fun onPartialResults(partialResults: Bundle) {
            if (mode == Mode.COMMAND) {
                val p = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!p.isNullOrBlank()) showBody("You: $p")
            }
        }

        override fun onError(error: Int) {
            if (stopped || mode == Mode.BUSY) return
            if (mode == Mode.COMMAND) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    val idle = System.currentTimeMillis() - lastActivityAt
                    if (idle > IDLE_CLOSE_MS) endConversation() else scheduleRestart(50)
                    return
                }
                commandErrors += 1
                if (commandErrors >= 4) {
                    commandErrors = 0
                    getSharedPreferences("cortex_prefs", Context.MODE_PRIVATE)
                        .edit().putString("lang", "en-US").apply()
                    showBody("Voice for that language is not available on this phone. Switched to English.")
                }
                resetRecognizer()
                scheduleRestart(500)
                return
            }
            var delay: Long
            var reset = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    delay = 50L
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    updateNotification("Microphone permission needed (error 9)")
                    stopSelf()
                    return
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    restartAttempts = (restartAttempts + 1).coerceAtMost(5)
                    delay = (400L * (1 shl (restartAttempts - 1))).coerceAtMost(6000L)
                    reset = true
                    updateNotification("Voice service error $error, retrying")
                }
                else -> {
                    delay = 500L
                    reset = true
                    updateNotification("Restarting listener (error $error)")
                }
            }
            if (reset) resetRecognizer()
            scheduleRestart(delay)
        }

        override fun onEndOfSpeech() {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting..."))
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cortex:wake")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
        } catch (e: Exception) {
        }
        initTts("com.google.android.tts")
        beginListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- recognizer

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("No speech service on this phone")
            return false
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        recognizer = r
        return true
    }

    private fun resetRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
    }

    private fun beginListening() {
        if (stopped || mode == Mode.BUSY) return
        if (!ensureRecognizer()) return
        val lang = if (mode == Mode.COMMAND) currentLang() else "en-US"
        val preferOffline = mode != Mode.COMMAND
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            resetRecognizer()
            scheduleRestart(1000)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (stopped) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun pauseRecognizer() {
        handler.removeCallbacks(restartRunnable)
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- conversation

    private fun onWake(remainder: String) {
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < 1500) {
            scheduleRestart(50)
            return
        }
        lastLaunchAt = now
        if (!canDrawOverlay()) {
            // No popup permission: fall back to opening the app like before.
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("voice_command", remainder)
            }
            startActivity(intent)
            scheduleRestart(50)
            return
        }
        history.clear()
        lastActivityAt = now
        commandErrors = 0
        showOverlay()
        if (remainder.length > 2 && currentLang() == "en-US") {
            ask(remainder)
        } else {
            mode = Mode.COMMAND
            showStatus("Listening...")
            showBody("Go ahead, I'm listening.")
            scheduleRestart(50)
        }
    }

    private fun currentLang(): String {
        return getSharedPreferences("cortex_prefs", Context.MODE_PRIVATE)
            .getString("lang", "ur-PK") ?: "ur-PK"
    }

    private fun handleCommandText(said: String) {
        lastActivityAt = System.currentTimeMillis()
        val lower = said.lowercase(Locale.ROOT).trim()
        val words = lower.split(Regex("\\s+")).size
        if (words <= 4) {
            if (STOP_PATTERN.containsMatchIn(lower) || lower.contains("band karo") ||
                lower.contains("بند کرو") || lower.contains("बंद करो")
            ) {
                endConversation()
                return
            }
            var newLang: String? = null
            if (lower.contains("english") || lower.contains("انگلش") || lower.contains("इंग्लिश")) {
                newLang = "en-US"
            } else if (lower.contains("urdu") || lower.contains("اردو")) {
                newLang = "ur-PK"
            } else if (lower.contains("hindi") || lower.contains("हिंदी") || lower.contains("ہندی")) {
                newLang = "hi-IN"
            }
            if (newLang != null) {
                getSharedPreferences("cortex_prefs", Context.MODE_PRIVATE)
                    .edit().putString("lang", newLang).apply()
                showBody("Language: $newLang")
                scheduleRestart(100)
                return
            }
        }
        // Ignore background noise (single short words) and repeats so we do not waste API calls.
        if (words < 2 && lower.length < 5) {
            scheduleRestart(50)
            return
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastAskAt < 3000 || (lower == lastAskText && nowMs - lastAskAt < 15000)) {
            scheduleRestart(50)
            return
        }
        lastAskAt = nowMs
        lastAskText = lower
        ask(said)
    }

    private fun ask(text: String) {
        mode = Mode.BUSY
        pauseRecognizer()
        handler.removeCallbacks(closeRunnable)
        showStatus("Thinking...")
        showBody("You: $text")
        history.add(Pair("user", text))
        while (history.size > 6) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
        val snapshot = ArrayList(history)
        Thread {
            val reply = try {
                callWorker(snapshot)
            } catch (e: Exception) {
                "ERR:Could not reach Cortex. Check your internet."
            }
            handler.post { onReply(reply) }
        }.start()
    }

    private fun onReply(raw: String) {
        if (stopped || mode != Mode.BUSY) return
        if (raw.startsWith("ERR:")) {
            val msg = raw.substring(4)
            showStatus("Cortex")
            showBody(msg)
            if (history.isNotEmpty()) history.removeAt(history.size - 1)
            lastActivityAt = System.currentTimeMillis()
            // Keep the popup open, wait a few seconds, then listen again.
            handler.postDelayed({
                if (!stopped && mode == Mode.BUSY) {
                    mode = Mode.COMMAND
                    lastActivityAt = System.currentTimeMillis()
                    showStatus("Listening...")
                    scheduleRestart(100)
                }
            }, 8000)
            return
        }
        val act = DeviceControl.parse(raw)
        if (act != null) {
            val result = try {
                DeviceControl.run(applicationContext, act.first, act.second)
            } catch (e: Exception) {
                "Sorry, I could not do that."
            }
            history.add(Pair("assistant", result))
            showStatus("Cortex")
            showBody(result)
            speakReply(result)
            return
        }
        val reply = raw.replace(Regex("[*#`_]"), "").trim()
        if (reply.isEmpty()) {
            endConversation()
            return
        }
        history.add(Pair("assistant", reply))
        showStatus("Cortex")
        showBody(reply)
        speakReply(reply)
    }

    /** Prefers Google's speech engine (much more natural than most phone-maker engines); falls back to the default. */
    private fun initTts(engine: String?) {
        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post { afterSpeaking() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { afterSpeaking() }
                    }
                })
                ttsReady = true
            } else if (engine != null) {
                try {
                    tts?.shutdown()
                } catch (e: Exception) {
                }
                tts = null
                initTts(null)
            }
        }, engine)
    }

    /** Picks the most natural installed voice for the language (high quality, network voices preferred). */
    private fun pickBestVoice(t: TextToSpeech, loc: Locale) {
        try {
            val all = t.voices ?: return
            var best: android.speech.tts.Voice? = null
            var bestScore = -1
            for (v in all) {
                if (v.locale.language != loc.language) continue
                val feats = v.features
                if (feats != null && feats.contains("notInstalled")) continue
                var score = v.quality
                if (v.locale.country == loc.country) score += 100
                if (v.isNetworkConnectionRequired) score += 50
                if (score > bestScore) {
                    bestScore = score
                    best = v
                }
            }
            if (best != null) t.voice = best
        } catch (e: Exception) {
        }
    }

    private fun speakReply(reply: String) {
        val t = tts
        if (!ttsReady || t == null) {
            handler.postDelayed({ afterSpeaking() }, 2500)
            return
        }
        val loc = when {
            reply.any { it in '\u0600'..'\u06FF' } -> Locale("ur", "PK")
            reply.any { it in '\u0900'..'\u097F' } -> Locale("hi", "IN")
            else -> Locale.US
        }
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Voice for this language is not installed: show the text, skip speaking.
            handler.postDelayed({ afterSpeaking() }, 3000)
            return
        }
        pickBestVoice(t, loc)
        t.setSpeechRate(0.95f)
        t.setPitch(1.0f)
        handler.removeCallbacks(speakWatchdog)
        handler.postDelayed(speakWatchdog, 40000)
        t.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "cortex-reply")
    }

    private fun afterSpeaking() {
        if (stopped || mode != Mode.BUSY) return
        handler.removeCallbacks(speakWatchdog)
        if (overlayRoot == null) {
            mode = Mode.WAKE
            scheduleRestart(100)
            return
        }
        mode = Mode.COMMAND
        lastActivityAt = System.currentTimeMillis()
        commandErrors = 0
        showStatus("Listening...")
        scheduleRestart(300)
    }

    private fun endConversation() {
        handler.removeCallbacks(closeRunnable)
        handler.removeCallbacks(speakWatchdog)
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        removeOverlay()
        mode = Mode.WAKE
        pauseRecognizer()
        scheduleRestart(150)
    }

    private fun callWorker(msgs: List<Pair<String, String>>): String {
        val prefs = getSharedPreferences("cortex_prefs", Context.MODE_PRIVATE)
        val license = prefs.getString("license", "") ?: ""
        if (license.isEmpty()) {
            return "ERR:Open the Cortex app once and add your license key in Settings."
        }
        val provider = prefs.getString("provider", "gemini") ?: "gemini"
        val arr = JSONArray()
        for (m in msgs) {
            val o = JSONObject()
            o.put("role", m.first)
            o.put("content", m.second)
            arr.put(o)
        }
        val body = JSONObject()
        body.put("provider", provider)
        body.put("messages", arr)
        body.put("system", SYSTEM_PROMPT)
        body.put("license_key", license)

        val conn = URL(WORKER_URL).openConnection() as HttpURLConnection
        val result: String = try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val j = JSONObject(txt)
            if (j.has("error")) "ERR:" + friendlyError(j.optString("error")) else j.optString("text", "")
        } finally {
            conn.disconnect()
        }
        return result
    }

    private fun friendlyError(code: String): String {
        return when (code) {
            "trial_expired" -> "Your trial has ended. Please renew your license."
            "revoked" -> "This license is not active."
            "not_found", "invalid_license" -> "License key not valid. Check it in the app Settings."
            "license_required" -> "Add your license key in the Cortex app Settings."
            else -> if (code.contains("quota", true) || code.contains("429") ||
                code.contains("exhausted", true) || code.contains("rate limit", true) ||
                code.contains("exceeded", true)
            ) {
                "Cortex is very busy right now. Please try again in a minute."
            } else {
                code
            }
        }
    }

    // ---------------------------------------------------------------- popup

    private fun canDrawOverlay(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun showOverlay() {
        if (overlayRoot != null) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val root = LinearLayout(this)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(dp(16), dp(12), dp(16), dp(14))
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#F00B1220"))
            bg.cornerRadius = dp(20).toFloat()
            bg.setStroke(dp(1), Color.parseColor("#4FD6E8"))
            root.background = bg

            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL

            val title = TextView(this)
            title.text = "CORTEX"
            title.setTextColor(Color.parseColor("#4FD6E8"))
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            title.letterSpacing = 0.2f
            val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            top.addView(title, titleParams)

            val status = TextView(this)
            status.text = "Listening..."
            status.setTextColor(Color.parseColor("#9AA4B2"))
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            status.setPadding(0, 0, dp(14), 0)
            top.addView(status)

            val close = TextView(this)
            close.text = "✕"
            close.setTextColor(Color.WHITE)
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            close.setOnClickListener { endConversation() }
            top.addView(close)

            root.addView(top)

            val body = TextView(this)
            body.text = ""
            body.setTextColor(Color.WHITE)
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            body.setPadding(0, dp(8), 0, 0)
            body.visibility = View.VISIBLE
            root.addView(body)

            val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = dp(48)

            wm.addView(root, params)
            overlayRoot = root
            overlayStatus = status
            overlayBody = body
        } catch (e: Exception) {
            overlayRoot = null
        }
    }

    private fun removeOverlay() {
        val v = overlayRoot ?: return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (e: Exception) {
        }
        overlayRoot = null
        overlayStatus = null
        overlayBody = null
    }

    private fun showStatus(t: String) {
        overlayStatus?.text = t
    }

    private fun showBody(t: String) {
        overlayBody?.text = t
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cortex wake word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps listening for \"Hey Cortex\" in the background"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        if (text == lastNotifText) return
        lastNotifText = text
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cortex is listening")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
