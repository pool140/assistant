package com.voicecontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Long-lived assistant service. The microphone is opened once through
 * ContinuousArabicAsr and remains open for the whole session; commands do not
 * cause SpeechRecognizer sessions to open/close.
 */
class VoiceListenerService : Service() {
    companion object {
        private const val TAG = "VoiceListenerService"
        private const val CHANNEL_ID = "voice_control_channel"
        private const val NOTIF_ID = 1
        private const val ACTION_PAUSE = "PAUSE"
        private const val ACTION_RESUME = "RESUME"
        var isRunning = false
            private set
    }

    private var asr: ContinuousArabicAsr? = null
    private var tts: TextToSpeech? = null
    @Volatile private var paused = false
    @Volatile private var stopped = false

    // Wake-word state: the microphone stays open continuously, but after
    // hearing "يا مساعد" the assistant remains armed briefly so the next
    // ASR segment can contain the actual command.
    @Volatile private var awaitingCommand = false
    @Volatile private var commandDeadlineElapsed = 0L
    private val commandWindowMs = 8_000L
    private val commandHandler by lazy { android.os.Handler(mainLooper) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        val notification = buildNotification("المساعد يستعد...")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ar")
        }

        asr = ContinuousArabicAsr(
            context = this,
            onText = { heard -> handleHeardText(heard) },
            onStatus = { updateNotification(it) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseListening()
            ACTION_RESUME -> resumeListening()
            else -> if (!paused) startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        awaitingCommand = false
        commandHandler.removeCallbacksAndMessages(null)
        isRunning = false
        asr?.close()
        asr = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (stopped || paused) return
        asr?.start()
    }

    private fun pauseListening() {
        paused = true
        awaitingCommand = false
        commandHandler.removeCallbacksAndMessages(null)
        asr?.stop()
        updateNotification("متوقف مؤقتًا")
    }

    private fun resumeListening() {
        paused = false
        asr?.start()
    }

    private fun handleHeardText(heardText: String) {
        if (paused || stopped) return

        val now = SystemClock.elapsedRealtime()
        val wake = normalize(CommandStore.getWakeWord(this)).ifBlank { "يا مساعد" }
        val normalized = normalize(heardText)
        val wakeVariants = listOf(
            wake,
            "يا مساعد",
            "يالمساعد",
            "يا مساعده",
            "يا مساعدي",
            "يا مساع"
        ).map { normalize(it) }.distinct()

        // Case 1: wake word and command arrived in the same ASR segment.
        val matched = wakeVariants.firstOrNull { normalized.contains(it) }
        if (matched != null) {
            val commandText = normalized.substringAfter(matched).trim()
            if (commandText.isBlank()) {
                armForCommand()
            } else {
                awaitingCommand = false
                commandHandler.removeCallbacksAndMessages(null)
                dispatch(heardText, commandText)
            }
            return
        }

        // Case 2: "يا مساعد" was recognized as a separate segment.
        // Accept the next speech segment as the command for a short window.
        if (awaitingCommand) {
            if (now <= commandDeadlineElapsed && normalized.isNotBlank()) {
                awaitingCommand = false
                commandHandler.removeCallbacksAndMessages(null)
                dispatch(heardText, normalized)
            } else {
                awaitingCommand = false
            }
        }
    }

    private fun armForCommand() {
        awaitingCommand = true
        commandDeadlineElapsed = SystemClock.elapsedRealtime() + commandWindowMs
        updateNotification("سمعتك، قول الأمر")
        commandHandler.removeCallbacksAndMessages(null)
        commandHandler.postDelayed({
            if (SystemClock.elapsedRealtime() >= commandDeadlineElapsed) {
                awaitingCommand = false
                updateNotification("المساعد يستمع باستمرار")
            }
        }, commandWindowMs)
    }

    private fun dispatch(originalText: String, commandText: String) {
        val parsed = CommandInterpreter.parse(this, commandText)
        val service = VoiceAccessibilityService.instance

        when (parsed) {
            is ParsedCommand.OpenApp -> {
                // Opening an app does NOT require Accessibility to be connected.
                val ok = try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(parsed.packageName)
                    if (launchIntent == null) false else {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(launchIntent)
                        true
                    }
                } catch (_: Throwable) { false }
                if (ok) {
                    confirm("تمام، فتحت ${parsed.label}")
                    CommandStore.appendLog(this, CommandLogEntry(System.currentTimeMillis(), originalText, "OK: opened ${parsed.packageName}"))
                } else {
                    speak("معرفتش افتح ${parsed.label}")
                    CommandStore.appendLog(this, CommandLogEntry(System.currentTimeMillis(), originalText, "FAILED: launch"))
                }
            }

            is ParsedCommand.RunAction -> {
                if (service == null) {
                    speak("لازم تفعّل صلاحية Accessibility الأول")
                    return
                }
                runAction(parsed.action, service, originalText)
            }

            is ParsedCommand.RunCombo -> {
                if (!openApp(parsed.combo.packageName)) {
                    speak("معرفتش افتح التطبيق")
                    return
                }
                asr?.muteFor(2500)
                service?.let { acc ->
                    android.os.Handler(mainLooper).postDelayed({
                        executeComboActions(parsed.combo, acc, originalText)
                    }, 1400)
                }
            }

            is ParsedCommand.ScrollCurrent -> {
                if (service == null) {
                    speak("لازم تفعّل صلاحية Accessibility الأول")
                    return
                }
                service.scrollFeed(parsed.direction) { ok ->
                    if (ok) confirm("تمام") else speak("معرفتش أمرر")
                }
            }

            is ParsedCommand.OpenVoiceChat -> {
                if (!openApp("com.openai.chatgpt")) {
                    speak("معرفتش افتح شات جي بي تي")
                    return
                }
                asr?.muteFor(4000)
                if (service == null) {
                    speak("لازم تفعّل Accessibility عشان أدوس زر المحادثة الصوتية")
                    return
                }
                android.os.Handler(mainLooper).postDelayed({
                    service.clickVoiceChatButton(5000) { ok ->
                        if (ok) confirm("تمام، فتحت المحادثة الصوتية")
                        else speak("ملقتش زر المحادثة الصوتية")
                    }
                }, 1400)
            }

            ParsedCommand.Unknown -> speak("معرفتش الأمر ده")
        }
    }

    private fun executeComboActions(combo: ComboCommand, service: VoiceAccessibilityService, originalText: String) {
        val actions = CommandStore.getActions(this)
        var remaining = combo.actionIds.toMutableList()
        fun next() {
            val id = remaining.removeFirstOrNull() ?: run {
                confirm("تمام، نفذت الأمر")
                return
            }
            val action = actions.firstOrNull { it.id == id } ?: return next()
            runAction(action, service, originalText) { next() }
        }
        next()
    }

    private fun runAction(action: CalibratedAction, service: VoiceAccessibilityService, originalText: String, next: (() -> Unit)? = null) {
        when (action.type) {
            "TAP" -> service.tap(action.x, action.y) { ok ->
                if (ok) confirm("تمام") else speak("معرفتش أدوس على الزرار")
                next?.invoke()
            }
            "SWIPE" -> service.swipe(action.x, action.y, action.x2, action.y2) { ok ->
                if (ok) confirm("تمام") else speak("معرفتش أعمل الحركة دي")
                next?.invoke()
            }
            else -> next?.invoke()
        }
    }

    private fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            true
        } catch (_: Throwable) { false }
    }

    private fun confirm(message: String) {
        if (CommandStore.isVoiceConfirmEnabled(this)) {
            asr?.muteFor(1600)
            speak(message)
        }
        updateNotification(message)
    }

    private fun speak(text: String) {
        asr?.muteFor(1400)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-confirm-${SystemClock.elapsedRealtime()}")
    }

    private fun normalize(s: String): String = s.trim()
        .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
        .replace("ى", "ي").replace("ة", "ه")
        .replace(Regex("[ًٌٍَُِّْـ]"), "")
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale("ar"))

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "مساعد التحكم الصوتي", NotificationManager.IMPORTANCE_LOW)
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
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
