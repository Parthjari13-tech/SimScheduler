package com.simscheduler.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class SimSchedule(
    val simSlot: Int,           // 0 = SIM 1, 1 = SIM 2
    val simName: String,        // e.g. "Jio", "LycaMobile"
    val simNumber: String,      // phone number
    val offHour: Int,           // hour to turn OFF (24h)
    val offMinute: Int,
    val onHour: Int,            // hour to turn ON (24h)
    val onMinute: Int,
    val isEnabled: Boolean      // schedule active or not
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("simSlot", simSlot)
        put("simName", simName)
        put("simNumber", simNumber)
        put("offHour", offHour)
        put("offMinute", offMinute)
        put("onHour", onHour)
        put("onMinute", onMinute)
        put("isEnabled", isEnabled)
    }

    companion object {
        fun fromJson(json: JSONObject) = SimSchedule(
            simSlot = json.getInt("simSlot"),
            simName = json.getString("simName"),
            simNumber = json.optString("simNumber", ""),
            offHour = json.getInt("offHour"),
            offMinute = json.getInt("offMinute"),
            onHour = json.getInt("onHour"),
            onMinute = json.getInt("onMinute"),
            isEnabled = json.getBoolean("isEnabled")
        )
    }
}

object ScheduleRepository {

    private const val PREFS_NAME = "sim_schedules"
    private const val KEY_SCHEDULES = "schedules"

    fun saveSchedules(context: Context, schedules: List<SimSchedule>) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = org.json.JSONArray()
        schedules.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(KEY_SCHEDULES, jsonArray.toString()).apply()
    }

    fun loadSchedules(context: Context): List<SimSchedule> {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SCHEDULES, null) ?: return emptyList()
        return try {
            val jsonArray = org.json.JSONArray(raw)
            (0 until jsonArray.length()).map { SimSchedule.fromJson(jsonArray.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Store the pending action for AccessibilityService to pick up
    fun setPendingAction(context: Context, simName: String, turnOff: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("pending_sim_name", simName)
            .putBoolean("pending_turn_off", turnOff)
            .putLong("pending_timestamp", System.currentTimeMillis())
            .apply()
    }

    fun getPendingAction(context: Context): Triple<String, Boolean, Long>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString("pending_sim_name", null) ?: return null
        val turnOff = prefs.getBoolean("pending_turn_off", true)
        val timestamp = prefs.getLong("pending_timestamp", 0L)
        return Triple(name, turnOff, timestamp)
    }

    fun clearPendingAction(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("pending_sim_name")
            .remove("pending_turn_off")
            .remove("pending_timestamp")
            .apply()
    }
}
