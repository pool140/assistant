package com.voicecontrol.app

import android.content.Context

sealed class ParsedCommand {
    data class OpenApp(val packageName: String, val label: String) : ParsedCommand()
    data class RunAction(val action: CalibratedAction) : ParsedCommand()
    data class RunCombo(val combo: ComboCommand) : ParsedCommand()
    data class ScrollCurrent(val direction: String) : ParsedCommand()
    data class TapByDescription(val phrase: String, val packageName: String? = null) : ParsedCommand()
    object Unknown : ParsedCommand()
}

object CommandInterpreter {
    private val OPEN_SYNONYMS = listOf("افتحلي", "افتح", "شغللي", "شغل", "ادخللي", "ادخل")
    private val SCROLL_UP = listOf("مرر لفوق", "مرر للاعلى", "مرر لأعلى", "اسكرول لفوق", "اسكرول للاعلى", "اطلع", "انزل الصفحة")
    private val SCROLL_DOWN = listOf("مرر لتحت", "مرر للاسفل", "مرر لأسفل", "اسكرول لتحت", "انزل", "نزل الصفحة")
    private val VOICE_BUTTON_HINTS = listOf("المحادثه الصوتيه", "المحادثة الصوتية", "التحدث", "زر التحدث", "الصوت", "محادثه صوت", "محادثة صوت")

    fun parse(ctx: Context, heardText: String): ParsedCommand {
        val text = normalize(heardText)

        for (combo in CommandStore.getCombos(ctx)) {
            if (text.contains(normalize(combo.label))) return ParsedCommand.RunCombo(combo)
        }

        for (action in CommandStore.getActions(ctx)) {
            if (text.contains(normalize(action.label))) return ParsedCommand.RunAction(action)
        }

        if (SCROLL_UP.any { text.contains(normalize(it)) }) return ParsedCommand.ScrollCurrent("up")
        if (SCROLL_DOWN.any { text.contains(normalize(it)) }) return ParsedCommand.ScrollCurrent("down")

        // Combined command: open an app and then find its voice/talk control.
        if (VOICE_BUTTON_HINTS.any { text.contains(normalize(it)) }) {
            val chatGpt = AppShortcut("شات جي بي تي", "com.openai.chatgpt", listOf("chatgpt", "شات جي بي تي", "شات جيبيتي", "جي بي تي"))
            if (matchesApp(text, chatGpt)) return ParsedCommand.TapByDescription("محادثة صوتية", chatGpt.packageName)
        }

        if (OPEN_SYNONYMS.any { text.contains(normalize(it)) }) {
            for (app in CommandStore.getApps(ctx)) {
                if (matchesApp(text, app)) return ParsedCommand.OpenApp(app.packageName, app.label)
            }
            // Fall back to the actual installed application labels, so the user
            // does not have to manually add every app to the shortcut list.
            val pm = ctx.packageManager
            for (info in pm.getInstalledApplications(0)) {
                val label = info.loadLabel(pm)?.toString().orEmpty()
                if (label.isNotBlank() && text.contains(normalize(label))) {
                    return ParsedCommand.OpenApp(info.packageName, label)
                }
            }
        }

        // "افتح محادثة صوت مع شات جي بي تي" is a semantic UI command.
        if (VOICE_BUTTON_HINTS.any { text.contains(normalize(it)) }) {
            return ParsedCommand.TapByDescription("محادثة صوتية", null)
        }

        for (app in CommandStore.getApps(ctx)) {
            if (matchesApp(text, app)) return ParsedCommand.OpenApp(app.packageName, app.label)
        }

        return ParsedCommand.Unknown
    }

    private fun matchesApp(text: String, app: AppShortcut): Boolean {
        val names = buildList {
            add(app.label)
            addAll(app.aliases)
            when (app.packageName) {
                "com.openai.chatgpt" -> addAll(listOf("chatgpt", "شات جي بي تي", "شات جيبيتي", "جي بي تي"))
                "com.facebook.katana" -> addAll(listOf("فيس", "الفيس", "فيسبوك", "الفيسبوك"))
                "com.whatsapp" -> addAll(listOf("واتس", "واتساب", "الواتساب"))
                "com.google.android.youtube" -> addAll(listOf("يوتيوب", "اليوتيوب"))
                "com.anthropic.claude" -> addAll(listOf("كلود", "claude"))
            }
        }
        return names.any { it.isNotBlank() && text.contains(normalize(it)) }
    }

    fun normalize(s: String): String = s.trim()
        .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
        .replace("ى", "ي").replace("ة", "ه")
        .replace(Regex("[ًٌٍَُِّْـ]"), "")
        .replace(Regex("\\s+"), " ")
        .lowercase()
}
