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

    companion object { private const val TAG = "AlarmReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        val simSlot = intent.getIntExtra("sim_slot", 0)
        val turnOff = intent.getBooleanExtra("turn_off", true)

        Log.d(TAG, "⏰ Alarm: slot=$simSlot turnOff=$turnOff")

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "SimScheduler::WakeLock"
        )
        wl.acquire(35_000L)

        Handler(Looper.getMainLooper()).postDelayed({
            ScheduleRepository.setPendingAction(context, simSlot, turnOff)

            val svc = SimAccessibilityService.instance
            if (svc != null) {
                svc.start(simSlot, turnOff)   // ← renamed from performSimToggle to start
                Log.d(TAG, "✅ Triggered slot=$simSlot")
            } else {
                Log.e(TAG, "❌ Accessibility Service not running")
            }

            reschedule(context, simSlot, turnOff)
        }, 1000)

        Handler(Looper.getMainLooper()).postDelayed({
            if (wl.isHeld) wl.release()
        }, 33_000L)
    }

    private fun reschedule(context: Context, simSlot: Int, turnOff: Boolean) {
        val schedule = loadSchedules(context).firstOrNull { it.simSlot == simSlot } ?: return
        AlarmScheduler.scheduleSimAlarms(context, schedule)
    }
}
