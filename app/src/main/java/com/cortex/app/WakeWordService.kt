package com.cortex.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

/**
 * Keeps a speech-recognition session running in the background and listens for "hey cortex".
 * The recognizer object is REUSED between sessions (no destroy/create each time) so the gap
 * between two listening windows is tiny. When the wake phrase is heard in a final result,
 * MainActivity is brought to the front with whatever was said after the phrase.
 */
class WakeWordService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var restartAttempts = 0
    private var stopped = false
    private var lastNotifText = ""
    private var lastLaunchAt = 0L

    private val restartRunnable = Runnable { beginListening() }

    companion object {
        const val CHANNEL_ID = "cortex_wake_channel"
        const val NOTIF_ID = 42
        val WAKE_PATTERN = Regex("""\b(hey\s+|hi\s+)?cortex\b[,]?\s*""", RegexOption.IGNORE_CASE)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            restartAttempts = 0
            updateNotification("Listening for \"Hey Cortex\"")
        }

        override fun onResults(results: Bundle) {
            handleTranscripts(results)
            scheduleRestart(50)
        }

        override fun onPartialResults(partialResults: Bundle) {}

        override fun onError(error: Int) {
            if (stopped) return
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
                    // RECOGNIZER_BUSY, CLIENT, AUDIO ...
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

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting..."))
        beginListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacks(restartRunnable)
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        if (stopped) return
        if (!ensureRecognizer()) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
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

    private fun handleTranscripts(bundle: Bundle) {
        val matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (transcript in matches) {
            val match = WAKE_PATTERN.find(transcript) ?: continue
            val remainder = transcript.substring(match.range.last + 1).trim()
            launchAppWithCommand(remainder)
            return
        }
    }

    private fun launchAppWithCommand(command: String) {
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < 2500) return
        lastLaunchAt = now
        updateNotification("Heard \"Hey Cortex\"")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("voice_command", command)
        }
        startActivity(intent)
    }

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
