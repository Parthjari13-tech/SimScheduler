package com.simscheduler.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.simscheduler.data.ScheduleRepository
import com.simscheduler.data.ScheduleRepository.loadSchedules
import com.simscheduler.service.SimAccessibilityService
import com.simscheduler.util.AlarmScheduler

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val simName = intent.getStringExtra("sim_name") ?: return
        val turnOff = intent.getBooleanExtra("turn_off", true)

        Log.d(TAG, "Alarm fired: $simName turnOff=$turnOff")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // Wake screen just enough for accessibility to work
        // Does NOT show anything to user — screen turns on briefly in background
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, // PARTIAL = CPU awake, screen may stay off
            "SimScheduler::AlarmWakeLock"
        )
        wakeLock.acquire(30_000L)

        // Small delay for system to be ready
        Handler(Looper.getMainLooper()).postDelayed({
            ScheduleRepository.setPendingAction(context, simName, turnOff)

            val service = SimAccessibilityService.instance
            if (service != null) {
                service.performSimToggle(simName, turnOff)
                Log.d(TAG, "Accessibility service triggered ✅")
            } else {
                Log.w(TAG, "Accessibility Service not running — user must enable it")
            }

            // Reschedule for next day
            rescheduleForNextDay(context, simName, turnOff)

        }, 500)

        // Release wake lock after 28 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            if (wakeLock.isHeld) wakeLock.release()
        }, 28_000L)
    }

    private fun rescheduleForNextDay(context: Context, simName: String, turnOff: Boolean) {
        val schedules = loadSchedules(context)
        val schedule = schedules.find { it.simName == simName } ?: return
        AlarmScheduler.scheduleSimAlarms(context, schedule)
    }
}
