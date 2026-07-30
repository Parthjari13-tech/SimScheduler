package com.simscheduler.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object HistoryRepository {

    private const val PREFS = "sim_history"
    private const val KEY   = "history_log"
    private const val MAX   = 50 // Keep last 50 entries

    fun addEntry(context: Context, simSlot: Int, turnOff: Boolean, success: Boolean) {
        val prefs   = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY, "") ?: ""
        val time    = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            .format(Date())
        val icon    = if (success) "✅" else "❌"
        val action  = if (turnOff) "OFF" else "ON"
        val entry   = "$icon SIM ${simSlot + 1} turned $action\n$time"

        val lines = current.split("|||").toMutableList()
        lines.add(0, entry)
        if (lines.size > MAX) lines.subList(MAX, lines.size).clear()

        prefs.edit().putString(KEY, lines.joinToString("|||")).apply()
    }

    fun getHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw   = prefs.getString(KEY, "") ?: ""
        return if (raw.isEmpty()) emptyList()
               else raw.split("|||").filter { it.isNotBlank() }
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
