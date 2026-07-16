package com.simscheduler.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.simscheduler.data.ScheduleRepository
import com.simscheduler.service.SimAccessibilityService
import com.simscheduler.util.AlarmScheduler
import com.simscheduler.data.ScheduleRepository.loadSchedules

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val simName = intent.getStringExtra("sim_name") ?: return
        val turnOff = intent.getBooleanExtra("turn_off", true)

        Log.d(TAG, "Alarm received: $simName → turnOff=$turnOff")

        // Acquire wake lock so device doesn't sleep during toggle
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "SimScheduler::AlarmWakeLock"
        )
        wakeLock.acquire(30_000L) // hold for up to 30 seconds

        // Store pending action so accessibility service can pick it up
        ScheduleRepository.setPendingAction(context, simName, turnOff)

        // Trigger accessibility service if it's running
        val accessibilityService = SimAccessibilityService.instance
        if (accessibilityService != null) {
            accessibilityService.performSimToggle(simName, turnOff)
        } else {
            Log.w(TAG, "Accessibility Service not running! User needs to enable it.")
        }

        // Re-schedule the same alarm for the next day (repeating daily)
        rescheduleForNextDay(context, simName, turnOff)

        // Release wake lock after 25 seconds (accessibility service should be done by then)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (wakeLock.isHeld) wakeLock.release()
        }, 25_000L)
    }

    private fun rescheduleForNextDay(context: Context, simName: String, turnOff: Boolean) {
        val schedules = loadSchedules(context)
        val schedule = schedules.find { it.simName == simName } ?: return

        // Re-set the specific alarm for next occurrence
        AlarmScheduler.scheduleSimAlarms(context, schedule)
    }
}
