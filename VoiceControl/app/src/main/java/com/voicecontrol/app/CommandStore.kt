package com.voicecontrol.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A calibrated on-screen action tied to a specific app (e.g. "tap the talk
 * button in Claude" or "swipe up to scroll Facebook feed").
 */
data class CalibratedAction(
    val id: String,          // unique id, e.g. "claude_talk_button"
    val label: String,       // Arabic phrase that triggers this, e.g. "اضغط على زر التحدث"
    val appPackage: String,  // which app this action belongs to
    val type: String,        // "TAP" or "SWIPE"
    val x: Float = 0f,
    val y: Float = 0f,
    val x2: Float = 0f,      // end point for SWIPE
    val y2: Float = 0f
)

/** A voice-triggered app shortcut, e.g. "افتح فيسبوك" -> com.facebook.katana */
data class AppShortcut(
    val label: String,
    val packageName: String
)

/** A composite command: open an app then immediately run one or more calibrated actions. */
data class ComboCommand(
    val label: String,           // e.g. "كلمني على كلود"
    val packageName: String,
    val actionIds: List<String>
)

data class CommandLogEntry(
    val timestamp: Long,
    val heardText: String,
    val result: String // "OK" or "FAILED: reason"
)

object CommandStore {
    private const val PREFS = "voice_control_prefs"
    private const val KEY_WAKE_WORD = "wake_word"
    private const val KEY_APPS = "apps"
    private const val KEY_ACTIONS = "actions"
    private const val KEY_COMBOS = "combos"
    private const val KEY_VOICE_CONFIRM = "voice_confirm"
    private const val KEY_AUTO_DRIVING = "auto_driving_mode"
    private const val KEY_LOG = "command_log"
    private const val MAX_LOG_ENTRIES = 100

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getWakeWord(ctx: Context): String =
        prefs(ctx).getString(KEY_WAKE_WORD, "افتحلي") ?: "افتحلي"

    fun setWakeWord(ctx: Context, word: String) {
        prefs(ctx).edit().putString(KEY_WAKE_WORD, word).apply()
    }

    fun isVoiceConfirmEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VOICE_CONFIRM, true)

    fun setVoiceConfirmEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VOICE_CONFIRM, enabled).apply()
    }

    fun isAutoDrivingModeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_DRIVING, false)

    fun setAutoDrivingModeEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_DRIVING, enabled).apply()
    }

    // ---------- Apps ----------

    fun getApps(ctx: Context): List<AppShortcut> {
        val raw = prefs(ctx).getString(KEY_APPS, null) ?: return defaultApps()
        val arr = JSONArray(raw)
        val list = mutableListOf<AppShortcut>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(AppShortcut(o.getString("label"), o.getString("packageName")))
        }
        return list
    }

    fun saveApps(ctx: Context, apps: List<AppShortcut>) {
        val arr = JSONArray()
        apps.forEach {
            val o = JSONObject()
            o.put("label", it.label)
            o.put("packageName", it.packageName)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_APPS, arr.toString()).apply()
    }

    private fun defaultApps(): List<AppShortcut> = listOf(
        AppShortcut("فيسبوك", "com.facebook.katana"),
        AppShortcut("شات جي بي تي", "com.openai.chatgpt"),
        AppShortcut("كلود", "com.anthropic.claude"),
        AppShortcut("واتساب", "com.whatsapp"),
        AppShortcut("يوتيوب", "com.google.android.youtube")
    )

    // ---------- Calibrated actions ----------

    fun getActions(ctx: Context): List<CalibratedAction> {
        val raw = prefs(ctx).getString(KEY_ACTIONS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val list = mutableListOf<CalibratedAction>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                CalibratedAction(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    appPackage = o.getString("appPackage"),
                    type = o.getString("type"),
                    x = o.optDouble("x", 0.0).toFloat(),
                    y = o.optDouble("y", 0.0).toFloat(),
                    x2 = o.optDouble("x2", 0.0).toFloat(),
                    y2 = o.optDouble("y2", 0.0).toFloat()
                )
            )
        }
        return list
    }

    fun saveActions(ctx: Context, actions: List<CalibratedAction>) {
        val arr = JSONArray()
        actions.forEach {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("label", it.label)
            o.put("appPackage", it.appPackage)
            o.put("type", it.type)
            o.put("x", it.x)
            o.put("y", it.y)
            o.put("x2", it.x2)
            o.put("y2", it.y2)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_ACTIONS, arr.toString()).apply()
    }

    fun addOrUpdateAction(ctx: Context, action: CalibratedAction) {
        val list = getActions(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == action.id }
        if (idx >= 0) list[idx] = action else list.add(action)
        saveActions(ctx, list)
    }

    // ---------- Combo commands ----------

    fun getCombos(ctx: Context): List<ComboCommand> {
        val raw = prefs(ctx).getString(KEY_COMBOS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val list = mutableListOf<ComboCommand>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val idsArr = o.getJSONArray("actionIds")
            val ids = mutableListOf<String>()
            for (j in 0 until idsArr.length()) ids.add(idsArr.getString(j))
            list.add(ComboCommand(o.getString("label"), o.getString("packageName"), ids))
        }
        return list
    }

    fun saveCombos(ctx: Context, combos: List<ComboCommand>) {
        val arr = JSONArray()
        combos.forEach {
            val o = JSONObject()
            o.put("label", it.label)
            o.put("packageName", it.packageName)
            o.put("actionIds", JSONArray(it.actionIds))
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_COMBOS, arr.toString()).apply()
    }

    // ---------- Log ----------

    fun appendLog(ctx: Context, entry: CommandLogEntry) {
        val log = getLog(ctx).toMutableList()
        log.add(0, entry)
        while (log.size > MAX_LOG_ENTRIES) log.removeAt(log.size - 1)
        val arr = JSONArray()
        log.forEach {
            val o = JSONObject()
            o.put("timestamp", it.timestamp)
            o.put("heardText", it.heardText)
            o.put("result", it.result)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_LOG, arr.toString()).apply()
    }

    fun getLog(ctx: Context): List<CommandLogEntry> {
        val raw = prefs(ctx).getString(KEY_LOG, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val list = mutableListOf<CommandLogEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(CommandLogEntry(o.getLong("timestamp"), o.getString("heardText"), o.getString("result")))
        }
        return list
    }
}
