package com.voicecontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Runs in the foreground the whole time the driver has the app active.
 * Continuously listens, waits for the wake word, then parses and executes
 * whatever command follows it.
 */
class VoiceListenerService : Service() {

    companion object {
        private const val TAG = "VoiceListenerService"
        private const val CHANNEL_ID = "voice_control_channel"
        private const val NOTIF_ID = 1
        var isRunning = false
            private set
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var paused = false // user can pause listening without stopping the service

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("بستنى الأمر..."))

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ar")
            }
        }

        initRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PAUSE" -> pauseListening()
            "RESUME" -> resumeListening()
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pauseListening() {
        paused = true
        speechRecognizer?.stopListening()
        updateNotification("متوقف مؤقتًا")
    }

    private fun resumeListening() {
        paused = false
        startListening()
    }

    private fun initRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = matches?.firstOrNull().orEmpty()
                    if (heard.isNotBlank()) handleHeardText(heard)
                    restartListeningSoon()
                }

                override fun onError(error: Int) {
                    // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT are expected in idle silence.
                    restartListeningSoon()
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (listening || paused) return
        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            listening = false
        }
    }

    private fun restartListeningSoon() {
        listening = false
        handler.postDelayed({ startListening() }, 300)
    }

    private fun handleHeardText(heardText: String) {
        val wakeWord = normalize(CommandStore.getWakeWord(this))
        val normalizedHeard = normalize(heardText)

        if (!normalizedHeard.contains(wakeWord)) {
            // Not directed at the assistant — ignore, this is the driver talking to someone else.
            return
        }

        val commandText = normalizedHeard.substringAfter(wakeWord).trim()
        dispatch(heardText, commandText)
    }

    private fun dispatch(originalText: String, commandText: String) {
        val parsed = CommandInterpreter.parse(this, commandText)
        val service = VoiceAccessibilityService.instance

        if (service == null) {
            speak("لازم تفعّل صلاحية Accessibility الأول")
            logResult(originalText, "FAILED: accessibility service not connected")
            return
        }

        when (parsed) {
            is ParsedCommand.OpenApp -> {
                val ok = service.launchApp(parsed.packageName)
                if (ok) {
                    confirm("تمام، فاتح ${parsed.label}")
                    logResult(originalText, "OK: opened ${parsed.packageName}")
                } else {
                    speak("معنديش تطبيق ${parsed.label} على الجهاز")
                    logResult(originalText, "FAILED: app not installed ${parsed.packageName}")
                }
            }

            is ParsedCommand.RunAction -> {
                runAction(parsed.action, service)
                logResult(originalText, "OK: ran action ${parsed.action.id}")
            }

            is ParsedCommand.RunCombo -> {
                val ok = service.launchApp(parsed.combo.packageName)
                if (!ok) {
                    speak("معرفتش افتح التطبيق")
                    logResult(originalText, "FAILED: combo app not installed")
                    return
                }
                confirm("تمام، فاتح وهنفذ الأمر")
                val actions = CommandStore.getActions(this)
                // Give the target app a moment to fully open before tapping.
                handler.postDelayed({
                    parsed.combo.actionIds.forEach { id ->
                        actions.find { it.id == id }?.let { runAction(it, service) }
                    }
                }, 1800)
                logResult(originalText, "OK: ran combo ${parsed.combo.label}")
            }

            is ParsedCommand.ScrollCurrent -> {
                service.scrollFeed { ok ->
                    if (!ok) {
                        speak("معرفتش أمرر")
                        logResult(originalText, "FAILED: scroll gesture failed")
                    }
                }
            }

            ParsedCommand.Unknown -> {
                speak("معرفتش الأمر ده")
                logResult(originalText, "FAILED: unrecognized command")
            }
        }
    }

    private fun runAction(action: CalibratedAction, service: VoiceAccessibilityService) {
        when (action.type) {
            "TAP" -> service.tap(action.x, action.y) { ok ->
                if (ok) confirm("تمام") else speak("معرفتش أدوس على الزرار، ممكن يبقى محتاج معايرة تاني")
            }
            "SWIPE" -> service.swipe(action.x, action.y, action.x2, action.y2) { ok ->
                if (ok) confirm("تمام") else speak("معرفتش أعمل الحركة دي")
            }
        }
    }

    private fun confirm(message: String) {
        if (CommandStore.isVoiceConfirmEnabled(this)) speak(message)
        updateNotification(message)
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun logResult(heardText: String, result: String) {
        CommandStore.appendLog(this, CommandLogEntry(System.currentTimeMillis(), heardText, result))
    }

    private fun normalize(s: String): String = s.trim()
        .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
        .replace("ى", "ي").replace("ة", "ه").lowercase()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "مساعد التحكم الصوتي", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("المساعد الصوتي شغال")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
