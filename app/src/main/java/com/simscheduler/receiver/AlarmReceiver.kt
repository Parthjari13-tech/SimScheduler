package com.simscheduler.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.simscheduler.data.ScheduleRepository.loadSchedules
import com.simscheduler.service.SimAccessibilityService
import com.simscheduler.ui.MainActivity
import com.simscheduler.util.AlarmScheduler
import com.simscheduler.util.SimToggleManager

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG      = "AlarmReceiver"
        private const val CHANNEL  = "sim_scheduler"
        private const val NOTIF_ID = 1001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val simSlot = intent.getIntExtra("sim_slot", 0)
        val turnOff = intent.getBooleanExtra("turn_off", true)

        Log.d(TAG, "⏰ Alarm: slot=$simSlot turnOff=$turnOff")

        createNotificationChannel(context)

        // Acquire partial wake lock — CPU stays on, screen stays OFF
        // This is truly background — no screen wake needed
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,  // ← CPU on, screen stays OFF
            "SimScheduler::BackgroundToggle"
        )
        wl.acquire(30_000L)

        Handler(Looper.getMainLooper()).post {
            // ── Try Method 1: Direct API (truly background, no UI) ────────────
            val directResult = SimToggleManager.toggleSim(context, simSlot, !turnOff)

            if (directResult.success) {
                // ✅ Direct API worked — completely silent background toggle
                Log.d(TAG, "✅ Direct API success: ${directResult.message}")
                showNotification(context, simSlot, turnOff, true, directResult.message)
            } else {
                // ❌ Direct API failed — fall back to Accessibility Service
                Log.w(TAG, "Direct API failed: ${directResult.message} — trying Accessibility")

                val svc = SimAccessibilityService.instance
                if (svc != null) {
                    // Accessibility needs screen ON temporarily
                    // Re-acquire with screen wake
                    wl.release()
                    @Suppress("DEPRECATION")
                    val screenWl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "SimScheduler::ScreenWake"
                    )
                    screenWl.acquire(35_000L)

                    svc.start(simSlot, turnOff)
                    Log.d(TAG, "Using Accessibility Service fallback")

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (screenWl.isHeld) screenWl.release()
                    }, 30_000L)
                } else {
                    // Both methods unavailable
                    Log.e(TAG, "❌ Both methods unavailable")
                    showNotification(context, simSlot, turnOff, false,
                        "Enable Accessibility Service in app")
                }
            }

            // Reschedule for next day
            reschedule(context, simSlot, turnOff)

            // Release wake lock
            Handler(Looper.getMainLooper()).postDelayed({
                if (wl.isHeld) wl.release()
            }, 5_000L)
        }
    }

    private fun reschedule(context: Context, simSlot: Int, turnOff: Boolean) {
        loadSchedules(context).firstOrNull { it.simSlot == simSlot }?.let {
            AlarmScheduler.scheduleSimAlarms(context, it)
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────
    private fun showNotification(
        context: Context,
        slot: Int,
        turnOff: Boolean,
        success: Boolean,
        message: String
    ) {
        val label  = "SIM ${slot + 1}"
        val action = if (turnOff) "OFF" else "ON"

        val title = if (success) "✅ $label turned $action"
                    else         "❌ Failed to turn $label $action"

        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(
                if (success) android.R.drawable.ic_dialog_info
                else         android.R.drawable.ic_dialog_alert
            )
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVibrate(longArrayOf(0, 250, 100, 250))
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL,
                "SIM Scheduler",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "SIM toggle results"
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }
}
