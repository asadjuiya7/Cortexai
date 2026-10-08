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
 * Keeps a speech-recognition session running in the background (real background —
 * this is what a browser tab can never do) and listens for "hey cortex". When heard,
 * it brings MainActivity to the front and hands off whatever was said right after
 * the wake phrase, so the web app's existing logic can take it from there.
 */
class WakeWordService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var restartAttempts = 0
    private var stopped = false

    companion object {
        const val CHANNEL_ID = "cortex_wake_channel"
        const val NOTIF_ID = 42
        val WAKE_PATTERN = Regex("""\b(hey\s+|hi\s+)?cortex\b[,]?\s*""", RegexOption.IGNORE_CASE)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (stopped) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    restartAttempts = 0
                }

                override fun onResults(results: Bundle) {
                    handleTranscripts(results)
                    restartListeningSoon(250)
                }

                override fun onPartialResults(partialResults: Bundle) {
                    handleTranscripts(partialResults)
                }

                override fun onError(error: Int) {
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 200L
                        else -> {
                            restartAttempts = (restartAttempts + 1).coerceAtMost(6)
                            (250L * (1 shl (restartAttempts - 1))).coerceAtMost(8000L)
                        }
                    }
                    restartListeningSoon(delay)
                }

                override fun onEndOfSpeech() {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            startListening(recognizerIntent)
        }
    }

    private fun restartListeningSoon(delayMs: Long) {
        if (stopped) return
        handler.postDelayed({ startListening() }, delayMs)
    }

    private fun handleTranscripts(bundle: Bundle) {
        val matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val transcript = matches.firstOrNull() ?: return
        val match = WAKE_PATTERN.find(transcript) ?: return
        val remainder = transcript.substring(match.range.last + 1).trim()
        launchAppWithCommand(remainder)
    }

    private fun launchAppWithCommand(command: String) {
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

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cortex is listening")
            .setContentText("Say \"Hey Cortex\" any time")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}
