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

        Log.d(TAG, "⏰ Alarm fired: $simName → ${if (turnOff) "OFF" else "ON"}")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP:
        // Turns the screen ON so Accessibility Service can read and interact
        // with the Settings screen. Required for the automation to work.
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "SimScheduler::ToggleWakeLock"
        )
        wakeLock.acquire(35_000L) // Hold for 35 seconds max

        // Wait 1 second for screen to fully wake up then trigger
        Handler(Looper.getMainLooper()).postDelayed({
            val service = SimAccessibilityService.instance
            if (service != null) {
                Log.d(TAG, "🚀 Triggering accessibility service")
                ScheduleRepository.setPendingAction(context, simName, turnOff)
                service.performSimToggle(simName, turnOff)
            } else {
                Log.e(TAG, "❌ Accessibility Service not running — user must enable it!")
            }

            // Reschedule same alarm for next day
            rescheduleForNextDay(context, simName, turnOff)

        }, 1000)

        // Release wake lock after 33 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "🔓 WakeLock released")
            }
        }, 33_000L)
    }

    private fun rescheduleForNextDay(context: Context, simName: String, turnOff: Boolean) {
        val schedules = loadSchedules(context)
        val schedule = schedules.find { it.simName == simName } ?: return
        AlarmScheduler.scheduleSimAlarms(context, schedule)
        Log.d(TAG, "📅 Rescheduled for next day")
    }
}
