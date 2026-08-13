package com.voicecontrol.app

import android.content.Context
import android.content.pm.PackageManager
import java.text.Normalizer
import java.util.Locale

sealed class ParsedCommand {
    data class OpenApp(val packageName: String, val label: String) : ParsedCommand()
    data class RunAction(val action: CalibratedAction) : ParsedCommand()
    data class RunCombo(val combo: ComboCommand) : ParsedCommand()
    data class ScrollCurrent(val direction: String) : ParsedCommand()
    object OpenVoiceChat : ParsedCommand()
    object Unknown : ParsedCommand()
}

object CommandInterpreter {
    private val OPEN = listOf("افتح", "افتحلي", "شغل", "شغللي", "ادخل", "ادخللي", "روح", "روحلي")
    private val SCROLL_UP = listOf("مرر لفوق", "مرر للاعلى", "مرر لأعلى", "اسحب لفوق", "اسكرول لفوق", "انزل لفوق")
    private val SCROLL_DOWN = listOf("مرر لتحت", "مرر للاسفل", "مرر لأسفل", "اسحب لتحت", "اسكرول لتحت")

    private val aliases = mapOf(
        "com.facebook.katana" to listOf(
            "فيسبوك", "فيس بوك", "الفيسبوك", "الفيس بوك", "فيس", "الفيس",
            "facebook", "face book"
        ),
        "com.openai.chatgpt" to listOf(
            "شات جي بي تي", "شات جى بى تى", "شات جيبيتي", "تشات جي بي تي",
            "شات جي بي", "شات جي", "chatgpt", "chat gpt"
        ),
        "com.whatsapp" to listOf("واتساب", "واتس اب", "واتس", "whatsapp"),
        "com.google.android.youtube" to listOf("يوتيوب", "يو تيوب", "youtube"),
        "com.anthropic.claude" to listOf("كلود", "claude"),
        "com.instagram.android" to listOf("انستجرام", "انستغرام", "انستا", "instagram"),
        "org.telegram.messenger" to listOf("تليجرام", "تلجرام", "تيليجرام", "telegram"),
        "com.zhiliaoapp.musically" to listOf("تيك توك", "تيكتوك", "tiktok")
    )

    fun parse(ctx: Context, heardText: String): ParsedCommand {
        val text = normalize(heardText)

        if (text.contains("محادثه صوت") || text.contains("محادثه صوتيه") ||
            text.contains("محادثه صوتية") || text.contains("المحادثه الصوتيه") ||
            text.contains("التحدث مع شات جي بي تي") || text.contains("تكلم مع شات جي بي تي")) {
            if (text.contains("شات") || text.contains("chat")) return ParsedCommand.OpenVoiceChat
        }

        for (combo in CommandStore.getCombos(ctx)) {
            if (text.contains(normalize(combo.label))) return ParsedCommand.RunCombo(combo)
        }
        for (action in CommandStore.getActions(ctx)) {
            if (text.contains(normalize(action.label))) return ParsedCommand.RunAction(action)
        }
        if (SCROLL_UP.any { text.contains(normalize(it)) }) return ParsedCommand.ScrollCurrent("up")
        if (SCROLL_DOWN.any { text.contains(normalize(it)) }) return ParsedCommand.ScrollCurrent("down")

        val apps = getLaunchableApps(ctx)
        val openRequested = OPEN.any { text.contains(normalize(it)) }
        if (openRequested || apps.isNotEmpty()) {
            val match = apps.sortedByDescending { score(text, it) }.firstOrNull { score(text, it) > 0 }
            if (match != null) return ParsedCommand.OpenApp(match.packageName, match.label)
        }
        return ParsedCommand.Unknown
    }

    private fun getLaunchableApps(ctx: Context): List<AppShortcut> {
        val pm = ctx.packageManager
        val map = LinkedHashMap<String, AppShortcut>()
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (info in installed) {
            val launch = pm.getLaunchIntentForPackage(info.packageName) ?: continue
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isNotBlank()) map[info.packageName] = AppShortcut(label, info.packageName)
        }
        for ((pkg, list) in aliases) {
            if (pm.getLaunchIntentForPackage(pkg) != null) {
                val current = map[pkg]
                val preferred = current?.copy(label = list.first()) ?: AppShortcut(list.first(), pkg)
                map[pkg] = preferred
            }
        }
        return map.values.toList()
    }

    private fun score(text: String, app: AppShortcut): Int {
        val pkg = app.packageName
        var best = 0
        val candidates = buildList {
            add(app.label)
            addAll(aliases[pkg].orEmpty())
        }
        for (candidate in candidates) {
            val n = normalize(candidate)
            if (n.isBlank()) continue
            if (text == n) best = maxOf(best, 100)
            else if (text.contains(n)) best = maxOf(best, 80 + n.length)
            else {
                val compactText = text.replace(" ", "")
                val compact = n.replace(" ", "")
                if (compact.length >= 4 && compactText.contains(compact)) best = maxOf(best, 60 + compact.length)
            }
        }
        return best
    }

    private fun normalize(s: String): String {
        val noDiacritics = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
        return noDiacritics.trim()
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ى", "ي").replace("ة", "ه")
            .replace("ـ", "")
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale("ar"))
    }
}
