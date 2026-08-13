package com.voicecontrol.app

import android.content.Context

sealed class ParsedCommand {
    data class OpenApp(val packageName: String, val label: String) : ParsedCommand()
    data class RunAction(val action: CalibratedAction) : ParsedCommand()
    data class RunCombo(val combo: ComboCommand) : ParsedCommand()
    data class ScrollCurrent(val direction: String) : ParsedCommand()
    object Unknown : ParsedCommand()
}

/**
 * Turns free-form heard Arabic text into a structured command.
 * Deliberately flexible: several synonyms map to the same intent so the
 * user doesn't have to say an exact fixed phrase every time.
 */
object CommandInterpreter {

    private val OPEN_SYNONYMS = listOf("افتحلي", "افتح", "شغللي", "شغل", "ادخللي", "ادخل")
    private val SCROLL_SYNONYMS = listOf("مرر", "نزل", "اسكرول", "لف الصفحة")
    private val TAP_SYNONYMS = listOf("دوس", "اضغط", "دوس على", "اضغط على")

    fun parse(ctx: Context, heardText: String): ParsedCommand {
        val text = normalize(heardText)

        // 1. Combo commands take priority (e.g. "كلمني على كلود")
        for (combo in CommandStore.getCombos(ctx)) {
            if (text.contains(normalize(combo.label))) {
                return ParsedCommand.RunCombo(combo)
            }
        }

        // 2. Explicit calibrated actions (e.g. "دوس على زرار التحدث")
        for (action in CommandStore.getActions(ctx)) {
            if (text.contains(normalize(action.label))) {
                return ParsedCommand.RunAction(action)
            }
        }

        // 3. Generic scroll on whatever app is currently open
        if (SCROLL_SYNONYMS.any { text.contains(normalize(it)) }) {
            return ParsedCommand.ScrollCurrent("down")
        }

        // 4. Open app: "افتح/افتحلي/شغل + اسم التطبيق"
        if (OPEN_SYNONYMS.any { text.contains(normalize(it)) }) {
            for (app in CommandStore.getApps(ctx)) {
                if (text.contains(normalize(app.label))) {
                    return ParsedCommand.OpenApp(app.packageName, app.label)
                }
            }
        }

        // 5. Fallback: check if any app name is mentioned at all, even without an explicit "open" verb
        for (app in CommandStore.getApps(ctx)) {
            if (text.contains(normalize(app.label))) {
                return ParsedCommand.OpenApp(app.packageName, app.label)
            }
        }

        return ParsedCommand.Unknown
    }

    /** Strips diacritics-insensitive noise and normalizes alef/ya variants for looser matching. */
    private fun normalize(s: String): String {
        return s.trim()
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ى", "ي")
            .replace("ة", "ه")
            .lowercase()
    }
}
