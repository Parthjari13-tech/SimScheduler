package com.simscheduler.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.simscheduler.data.ScheduleRepository
import com.simscheduler.util.AlarmScheduler

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d("BootReceiver", "Device booted — restoring SIM schedules")

            val schedules = ScheduleRepository.loadSchedules(context)
            if (schedules.isNotEmpty()) {
                AlarmScheduler.scheduleAll(context, schedules)
                Log.d("BootReceiver", "Restored ${schedules.size} schedules")
            }
        }
    }
}
