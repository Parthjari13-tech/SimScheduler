package com.simscheduler.receiver

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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

        Log.d(TAG, "Alarm received: $simName → turnOff=$turnOff")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // Wake the screen — works even on locked phone
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "SimScheduler::AlarmWakeLock"
        )
        wakeLock.acquire(30_000L)

        // Dismiss keyguard if phone is locked (Android 8+)
        dismissKeyguardIfNeeded(context)

        // Small delay to let screen fully wake up before opening Settings
        Handler(Looper.getMainLooper()).postDelayed({
            ScheduleRepository.setPendingAction(context, simName, turnOff)

            val accessibilityService = SimAccessibilityService.instance
            if (accessibilityService != null) {
                accessibilityService.performSimToggle(simName, turnOff)
            } else {
                Log.w(TAG, "Accessibility Service not running!")
            }

            // Re-schedule for next day
            rescheduleForNextDay(context, simName, turnOff)
        }, 1000)

        // Release wake lock after 28 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            if (wakeLock.isHeld) wakeLock.release()
        }, 28_000L)
    }

    private fun dismissKeyguardIfNeeded(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (keyguardManager.isKeyguardLocked) {
                    Log.d(TAG, "Phone is locked — waking screen to show above lockscreen")
                    // We use FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP which wakes the screen
                    // Settings will open above the lockscreen automatically
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling keyguard: ${e.message}")
        }
    }

    private fun rescheduleForNextDay(context: Context, simName: String, turnOff: Boolean) {
        val schedules = loadSchedules(context)
        val schedule = schedules.find { it.simName == simName } ?: return
        AlarmScheduler.scheduleSimAlarms(context, schedule)
    }
}
