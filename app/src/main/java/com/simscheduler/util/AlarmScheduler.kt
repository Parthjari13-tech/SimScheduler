package com.simscheduler.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.simscheduler.data.SimSchedule
import com.simscheduler.receiver.AlarmReceiver
import java.util.Calendar

object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    fun scheduleAll(context: Context, schedules: List<SimSchedule>) {
        schedules.forEach { s ->
            if (s.isEnabled) scheduleSimAlarms(context, s)
            else cancelSimAlarms(context, s.simSlot)
        }
    }

    fun scheduleSimAlarms(context: Context, s: SimSchedule) {
        // OFF alarm: request code 1000 + (slot * 2)
        setAlarm(context,
            requestCode = 1000 + (s.simSlot * 2),
            hour = s.offHour, minute = s.offMinute,
            simSlot = s.simSlot, turnOff = true)

        // ON alarm: request code 1001 + (slot * 2)
        setAlarm(context,
            requestCode = 1001 + (s.simSlot * 2),
            hour = s.onHour, minute = s.onMinute,
            simSlot = s.simSlot, turnOff = false)

        Log.d(TAG, "Scheduled slot ${s.simSlot}: " +
            "OFF ${s.offHour}:${s.offMinute} | ON ${s.onHour}:${s.onMinute}")
    }

    fun cancelSimAlarms(context: Context, simSlot: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(1000 + simSlot * 2, 1001 + simSlot * 2).forEach { code ->
            val pi = PendingIntent.getBroadcast(context, code,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            pi?.let { am.cancel(it) }
        }
        Log.d(TAG, "Cancelled alarms for slot $simSlot")
    }

    private fun setAlarm(
        context: Context,
        requestCode: Int,
        hour: Int, minute: Int,
        simSlot: Int, turnOff: Boolean
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.simscheduler.ACTION_TOGGLE_SIM"
            putExtra("sim_slot", simSlot)   // ← slot number, not name
            putExtra("turn_off", turnOff)
        }

        val pi = PendingIntent.getBroadcast(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis())
                add(Calendar.DAY_OF_MONTH, 1)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }

        Log.d(TAG, "Alarm set: slot=$simSlot ${if (turnOff) "OFF" else "ON"} at $hour:$minute")
    }
}
