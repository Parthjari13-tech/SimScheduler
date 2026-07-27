package com.simscheduler.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class SimSchedule(
    val simSlot: Int,       // 0 = SIM 1, 1 = SIM 2
    val simLabel: String,   // Display label e.g. "SIM 1" or "SIM 2"
    val offHour: Int,
    val offMinute: Int,
    val onHour: Int,
    val onMinute: Int,
    val isEnabled: Boolean
) {
    fun toJson() = JSONObject().apply {
        put("simSlot",    simSlot)
        put("simLabel",   simLabel)
        put("offHour",    offHour)
        put("offMinute",  offMinute)
        put("onHour",     onHour)
        put("onMinute",   onMinute)
        put("isEnabled",  isEnabled)
    }

    companion object {
        fun fromJson(j: JSONObject) = SimSchedule(
            simSlot   = j.getInt("simSlot"),
            simLabel  = j.optString("simLabel", "SIM ${j.getInt("simSlot") + 1}"),
            offHour   = j.getInt("offHour"),
            offMinute = j.getInt("offMinute"),
            onHour    = j.getInt("onHour"),
            onMinute  = j.getInt("onMinute"),
            isEnabled = j.getBoolean("isEnabled")
        )
    }
}

object ScheduleRepository {

    private const val PREFS  = "sim_schedules"
    private const val KEY    = "schedules"

    fun saveSchedules(context: Context, schedules: List<SimSchedule>) {
        val arr = JSONArray()
        schedules.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun loadSchedules(context: Context): List<SimSchedule> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { SimSchedule.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun setPendingAction(context: Context, simSlot: Int, turnOff: Boolean) {
        prefs(context).edit()
            .putInt("pending_slot",     simSlot)
            .putBoolean("pending_off",  turnOff)
            .putLong("pending_ts",      System.currentTimeMillis())
            .apply()
    }

    fun getPendingAction(context: Context): Triple<Int, Boolean, Long>? {
        val p = prefs(context)
        if (!p.contains("pending_slot")) return null
        return Triple(
            p.getInt("pending_slot", 0),
            p.getBoolean("pending_off", true),
            p.getLong("pending_ts", 0L)
        )
    }

    fun clearPendingAction(context: Context) {
        prefs(context).edit()
            .remove("pending_slot")
            .remove("pending_off")
            .remove("pending_ts")
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
