package com.cortex.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

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

    // Offline wake engine (Vosk)
    private var voskModel: Model? = null
    @Volatile private var voskReady = false
    @Volatile private var voskLoading = false
    private var speechSvc: SpeechService? = null
    private var voskTriggered = false
    private var spkModel: SpeakerModel? = null
    private var beepMuted = false
    private var steps = 0
    private var enrollLeft = 0
    private val enrollVecs = ArrayList<DoubleArray>()

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
        const val VOSK_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val VOSK_DIR = "vosk-model-small-en-us-0.15"
        const val VOSK_GRAMMAR =
            "[\"hey cortex\", \"hi cortex\", \"ok cortex\", \"okay cortex\", \"cortex\", " +
                "\"hey cortes\", \"cortes\", \"hey codex\", \"[unk]\"]"
        const val MAX_STEPS = 8
        val CHAIN_ACTIONS = setOf(
            "open_app", "web_search", "youtube_search", "open_url", "navigate", "whatsapp_message", "whatsapp",
            "send_sms", "sms", "send_email", "email", "click_text", "type_text", "scroll_down", "scroll_up",
            "read_screen", "go_back", "go_home", "recents", "notifications"
        )
        const val SPK_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
        const val SPK_DIR = "vosk-model-spk-0.4"
        const val SPK_THRESHOLD = 0.65
        val SAFE_WHEN_LOCKED = setOf(
            "flashlight_on", "flashlight_off", "volume_up", "volume_down", "mute", "unmute",
            "battery_status", "set_alarm", "set_timer"
        )
        val VOSK_WAKE = Regex("""\b(?:cortex|cortes|codex)\b""")
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
        prepareVoskModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        unmuteBeep()
        stopVosk()
        try {
            voskModel?.close()
        } catch (e: Throwable) {
        }
        voskModel = null
        voskReady = false
        try {
            spkModel?.close()
        } catch (e: Throwable) {
        }
        spkModel = null
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
        if (mode == Mode.WAKE && voskReady) {
            // Offline engine: no beeps, no gaps. Free the system recognizer first.
            try {
                recognizer?.cancel()
            } catch (e: Exception) {
            }
            startVosk()
            return
        }
        stopVosk()
        if (mode == Mode.COMMAND) muteBeep() else unmuteBeep()
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
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
        unmuteBeep()
        stopVosk()
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- offline wake (Vosk)

    private fun prepareVoskModel() {
        if (voskReady || voskLoading) return
        voskLoading = true
        Thread {
            try {
                val root = File(filesDir, "vosk")
                val modelDir = File(root, VOSK_DIR)
                val marker = File(root, ".ok")
                if (!marker.exists() || !modelDir.isDirectory) {
                    root.deleteRecursively()
                    root.mkdirs()
                    val zip = File(root, "model.zip")
                    postNotif("Downloading offline voice (one time)...")
                    download(VOSK_URL, zip)
                    postNotif("Preparing offline voice...")
                    unzip(zip, root)
                    zip.delete()
                    if (!modelDir.isDirectory) throw RuntimeException("model folder missing")
                    marker.writeText("ok")
                }
                try {
                    val spkDir = File(root, SPK_DIR)
                    val spkMarker = File(root, ".spk_ok")
                    if (!spkMarker.exists() || !spkDir.isDirectory) {
                        postNotif("Downloading voice-match model...")
                        val z = File(root, "spk.zip")
                        download(SPK_URL, z)
                        unzip(z, root)
                        z.delete()
                        if (spkDir.isDirectory) spkMarker.writeText("ok")
                    }
                    if (spkDir.isDirectory) spkModel = SpeakerModel(spkDir.absolutePath)
                } catch (e: Throwable) {
                    spkModel = null
                }
                val m = Model(modelDir.absolutePath)
                handler.post {
                    if (stopped) {
                        try {
                            m.close()
                        } catch (e: Throwable) {
                        }
                    } else {
                        voskModel = m
                        voskReady = true
                        voskLoading = false
                        updateNotification("Listening for \"Hey Cortex\" (offline)")
                        if (mode == Mode.WAKE) {
                            handler.removeCallbacks(restartRunnable)
                            beginListening()
                        }
                    }
                }
            } catch (e: Throwable) {
                voskLoading = false
                postNotif("Offline voice unavailable, using standard listener")
            }
        }.start()
    }

    private fun postNotif(t: String) {
        handler.post { if (!stopped) updateNotification(t) }
    }

    private fun download(urlStr: String, dest: File) {
        var url = URL(urlStr)
        var conn: HttpURLConnection? = null
        for (i in 0 until 5) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            if (code != 200) throw RuntimeException("HTTP $code")
            break
        }
        conn!!.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        conn.disconnect()
    }

    private fun unzip(zip: File, destDir: File) {
        val base = destDir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val f = File(destDir, e.name)
                if (!f.canonicalPath.startsWith(base + File.separator)) continue // zip-slip guard
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
        }
    }

    private val voskListener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            check(hypothesis, "partial")
        }

        override fun onResult(hypothesis: String?) {
            check(hypothesis, "text")
        }

        override fun onFinalResult(hypothesis: String?) {}

        override fun onError(exception: Exception?) {
            if (stopped) return
            handler.post {
                stopVosk()
                scheduleRestart(1000)
            }
        }

        override fun onTimeout() {}

        private fun check(json: String?, key: String) {
            if (stopped || mode != Mode.WAKE || voskTriggered || json == null) return
            val obj = try {
                JSONObject(json)
            } catch (e: Exception) {
                return
            }
            val text = obj.optString(key, "")
            if (text.isEmpty() || !VOSK_WAKE.containsMatchIn(text.lowercase())) return
            val lockOn = voiceLockOn()
            if (enrollLeft > 0 || lockOn) {
                // Need the speaker vector, which only comes with the final result.
                if (key != "text") return
                val vec = readSpk(obj)
                if (enrollLeft > 0) {
                    if (vec == null) {
                        postNotif("Voice-match model not ready yet")
                        return
                    }
                    enrollVecs.add(vec)
                    enrollLeft -= 1
                    if (enrollLeft == 0) finishEnroll() else
                        postNotif("Say \"Hey Cortex\" again ($enrollLeft left)")
                    return
                }
                val owner = loadOwner()
                if (owner != null && vec != null) {
                    val d = cosineDist(owner, vec)
                    postNotif("Voice match distance " + String.format(Locale.US, "%.2f", d))
                    if (d > SPK_THRESHOLD) return // not the owner: ignore
                }
            }
            voskTriggered = true
            handler.post { onWake("") }
        }
    }

    private fun readSpk(o: JSONObject): DoubleArray? {
        val a = o.optJSONArray("spk") ?: return null
        val v = DoubleArray(a.length())
        for (i in 0 until a.length()) v[i] = a.optDouble(i)
        return if (v.isEmpty()) null else v
    }

    private fun cosineDist(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 1.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 1.0
        return 1.0 - dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    private fun voiceLockOn(): Boolean =
        getSharedPreferences("cortex_prefs", Context.MODE_PRIVATE).getBoolean("voice_lock", false) &&
            loadOwner() != null && spkModel != null

    private fun loadOwner(): DoubleArray? {
        val str = getSharedPreferences("cortex_prefs", Context.MODE_PRIVATE)
            .getString("owner_voice", null) ?: return null
        return try {
            val a = JSONArray(str)
            DoubleArray(a.length()) { a.getDouble(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun finishEnroll() {
        val n = enrollVecs[0].size
        val avg = DoubleArray(n)
        for (v in enrollVecs) for (i in 0 until n) avg[i] += v[i] / enrollVecs.size
        val arr = JSONArray()
        for (x in avg) arr.put(x)
        getSharedPreferences("cortex_prefs", Context.MODE_PRIVATE).edit()
            .putString("owner_voice", arr.toString()).putBoolean("voice_lock", true).apply()
        enrollVecs.clear()
        postNotif("Your voice is saved. Voice lock is ON")
        handler.post { speakReply("Your voice is saved. Voice lock is on.") }
    }

    private fun startEnroll() {
        if (spkModel == null) {
            showBody("Voice-match model is not ready yet. Try again in a minute on Wi-Fi.")
            speakReply("Voice match is not ready yet.")
            return
        }
        enrollVecs.clear()
        enrollLeft = 3
        showBody("Close this and say \"Hey Cortex\" 3 times, one by one.")
        postNotif("Say \"Hey Cortex\" 3 times")
    }

    /** Silences the start/stop "ding" of the system recogniser while the popup is listening. */
    private fun muteBeep() {
        if (beepMuted) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.isStreamMute(AudioManager.STREAM_MUSIC)) return
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            beepMuted = true
        } catch (e: Throwable) {
        }
    }

    private fun unmuteBeep() {
        if (!beepMuted) return
        beepMuted = false
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Throwable) {
        }
    }

    private fun isDeviceLocked(): Boolean {
        return try {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        } catch (e: Exception) {
            false
        }
    }

    private fun startVosk() {
        if (speechSvc != null) return
        val m = voskModel ?: return
        try {
            val rec = try {
                Recognizer(m, 16000.0f, VOSK_GRAMMAR)
            } catch (e: Throwable) {
                Recognizer(m, 16000.0f)
            }
            try {
                spkModel?.let { rec.setSpeakerModel(it) }
            } catch (e: Throwable) {
            }
            val svc = SpeechService(rec, 16000.0f)
            voskTriggered = false
            if (!svc.startListening(voskListener)) {
                svc.shutdown()
                throw RuntimeException("mic busy")
            }
            speechSvc = svc
            updateNotification("Listening for \"Hey Cortex\" (offline)")
        } catch (e: Throwable) {
            speechSvc = null
            scheduleRestart(1500)
        }
    }

    private fun stopVosk() {
        val s = speechSvc ?: return
        speechSvc = null
        try {
            s.stop()
        } catch (e: Throwable) {
        }
        try {
            s.shutdown()
        } catch (e: Throwable) {
        }
    }

    // ---------------------------------------------------------------- conversation

    private fun onWake(remainder: String) {
        stopVosk()
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
        if (words <= 6) {
            val enrollWords = lower.contains("yaad kar") || lower.contains("remember my voice") ||
                lower.contains("save my voice") || lower.contains("یاد کر") || lower.contains("याद कर")
            val voiceWord = lower.contains("awaaz") || lower.contains("awaz") || lower.contains("voice") ||
                lower.contains("آواز") || lower.contains("आवाज")
            if (enrollWords && voiceWord) {
                mode = Mode.BUSY
                pauseRecognizer()
                startEnroll()
                handler.postDelayed({ if (!stopped) endConversation() }, 6000)
                return
            }
            if (voiceWord && (lower.contains("lock off") || lower.contains("lock band") || lower.contains("لاک بند"))) {
                getSharedPreferences("cortex_prefs", Context.MODE_PRIVATE).edit().putBoolean("voice_lock", false).apply()
                showBody("Voice lock is off.")
                scheduleRestart(100)
                return
            }
            if (voiceWord && (lower.contains("lock on") || lower.contains("lock chalu") || lower.contains("لاک آن"))) {
                getSharedPreferences("cortex_prefs", Context.MODE_PRIVATE).edit().putBoolean("voice_lock", true).apply()
                showBody(if (loadOwner() != null) "Voice lock is on." else "First say: remember my voice.")
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
        steps = 0
        callAgent()
    }

    private fun callAgent() {
        while (history.size > 16) history.removeAt(0)
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
            val locked = isDeviceLocked()
            if (locked && act.first !in SAFE_WHEN_LOCKED) {
                val msg = "Phone locked hai. Is kaam ke liye pehle unlock karein."
                history.add(Pair("assistant", msg))
                showStatus("Cortex")
                showBody(msg)
                speakReply(msg)
                return
            }
            val result = try {
                DeviceControl.run(applicationContext, act.first, act.second)
            } catch (e: Exception) {
                "Sorry, I could not do that."
            }
            steps += 1
            showStatus("Working... ($steps)")
            showBody(result.take(160))
            if (act.first !in CHAIN_ACTIONS || steps >= MAX_STEPS) {
                history.add(Pair("assistant", raw))
                history.add(Pair("user", "[PHONE] $result"))
                val say = if (act.first == "read_screen") "Done." else result.take(200)
                history.add(Pair("assistant", say))
                showStatus("Cortex")
                showBody(say)
                speakReply(say)
                return
            }
            history.add(Pair("assistant", raw))
            val wait = when (act.first) {
                "open_app", "web_search", "youtube_search", "open_url", "navigate",
                "whatsapp_message", "whatsapp", "send_sms", "sms", "send_email", "email" -> 3500L
                else -> 1500L
            }
            handler.postDelayed({
                if (stopped || mode != Mode.BUSY) return@postDelayed
                val screen = try {
                    CortexAccessibilityService.instance?.screenText() ?: ""
                } catch (e: Exception) {
                    ""
                }
                val msg = "[PHONE] $result" + if (screen.isNotEmpty() && !result.startsWith("Screen:")) " Screen: $screen" else ""
                history.add(Pair("user", msg))
                callAgent()
            }, wait)
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
