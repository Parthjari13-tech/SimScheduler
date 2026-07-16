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

    // Request code base — each SIM has 2 alarms (OFF and ON)
    // SIM 0 OFF = 1000, SIM 0 ON = 1001
    // SIM 1 OFF = 1002, SIM 1 ON = 1003

    fun scheduleAll(context: Context, schedules: List<SimSchedule>) {
        schedules.forEach { schedule ->
            if (schedule.isEnabled) {
                scheduleSimAlarms(context, schedule)
            } else {
                cancelSimAlarms(context, schedule.simSlot)
            }
        }
    }

    fun scheduleSimAlarms(context: Context, schedule: SimSchedule) {
        // Schedule OFF alarm
        setAlarm(
            context = context,
            requestCode = 1000 + (schedule.simSlot * 2),
            hour = schedule.offHour,
            minute = schedule.offMinute,
            simName = schedule.simName,
            turnOff = true
        )

        // Schedule ON alarm
        setAlarm(
            context = context,
            requestCode = 1001 + (schedule.simSlot * 2),
            hour = schedule.onHour,
            minute = schedule.onMinute,
            simName = schedule.simName,
            turnOff = false
        )

        Log.d(TAG, "Scheduled alarms for ${schedule.simName}: " +
                "OFF at ${schedule.offHour}:${schedule.offMinute}, " +
                "ON at ${schedule.onHour}:${schedule.onMinute}")
    }

    fun cancelSimAlarms(context: Context, simSlot: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelAlarm(context, alarmManager, 1000 + (simSlot * 2))
        cancelAlarm(context, alarmManager, 1001 + (simSlot * 2))
        Log.d(TAG, "Cancelled alarms for SIM slot $simSlot")
    }

    private fun setAlarm(
        context: Context,
        requestCode: Int,
        hour: Int,
        minute: Int,
        simName: String,
        turnOff: Boolean
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.simscheduler.ACTION_TOGGLE_SIM"
            putExtra("sim_name", simName)
            putExtra("turn_off", turnOff)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Calculate next trigger time
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            // If time already passed today, schedule for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        // Use setExactAndAllowWhileIdle to fire even in Doze mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        }

        Log.d(TAG, "Alarm set: $simName ${if (turnOff) "OFF" else "ON"} at $hour:$minute")
    }

    private fun cancelAlarm(context: Context, alarmManager: AlarmManager, requestCode: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }
}
