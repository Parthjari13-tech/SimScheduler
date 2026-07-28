package com.simscheduler.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.simscheduler.R
import com.simscheduler.data.ScheduleRepository
import com.simscheduler.ui.MainActivity

class SimAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SimSvc"
        private const val CHANNEL_ID = "sim_scheduler_channel"
        private const val NOTIF_ID = 1001

        var instance: SimAccessibilityService? = null

        fun triggerSimToggle(simSlot: Int, turnOff: Boolean) {
            instance?.start(simSlot, turnOff)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var targetSlot = 0
    private var wantOff = false
    private var active = false

    private enum class Step {
        FIND_SIM_MENU,
        TOGGLE_SIM_SWITCH,
        CONFIRM_POPUP
    }
    private var step = Step.FIND_SIM_MENU

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
        }
        createNotificationChannel()
        Log.d(TAG, "✅ Service connected")
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); instance = null }

    // ── Entry point ───────────────────────────────────────────────────────────
    fun start(simSlot: Int, turnOff: Boolean) {
        targetSlot = simSlot
        wantOff    = turnOff
        active     = true
        step       = Step.FIND_SIM_MENU

        Log.d(TAG, "▶ Start: slot=$simSlot wantOff=$turnOff")

        // Show "working" notification immediately
        showWorkingNotification(simSlot, turnOff)

        // Open main Settings in background
        openMainSettings()

        // Timeout after 30 seconds
        handler.postDelayed({
            if (active) {
                Log.w(TAG, "⏰ Timeout")
                showResultNotification(
                    simSlot = simSlot,
                    turnOff = turnOff,
                    success = false,
                    reason  = "Timeout — Settings did not respond"
                )
                done()
            }
        }, 30_000)
    }

    // ── React to screen changes ───────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!active) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.settings") return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ handleScreen() }, 700)
    }

    private fun handleScreen() {
        if (!active) return
        val root = rootInActiveWindow ?: return
        val texts = getTexts(root)
        Log.d(TAG, "Step=$step | Texts=$texts")

        when (step) {
            Step.FIND_SIM_MENU     -> findAndTapSimMenu(root, texts)
            Step.TOGGLE_SIM_SWITCH -> toggleCorrectSimSwitch(root)
            Step.CONFIRM_POPUP     -> clickConfirmation(root)
        }
    }

    // ── STEP 1: Find SIM menu and tap it ─────────────────────────────────────
    private fun findAndTapSimMenu(root: AccessibilityNodeInfo, texts: List<String>) {
        val allSwitches = getAllSwitches(root)
        Log.d(TAG, "Switches: ${allSwitches.size}")

        if (allSwitches.size >= 2) {
            Log.d(TAG, "✅ SIM list detected")
            step = Step.TOGGLE_SIM_SWITCH
            toggleCorrectSimSwitch(root)
            return
        }

        val simMenuKeywords = listOf(
            "SIMs & mobile network",
            "SIM & mobile network",
            "SIM cards",
            "Mobile network",
            "SIM",
            "Network & internet",
            "Connections"
        )

        for (keyword in simMenuKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (nodes.isNotEmpty()) {
                val clickable = findClickableParent(nodes[0]) ?: nodes[0]
                val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Tapped '$keyword': $clicked")
                if (clicked) {
                    step = Step.FIND_SIM_MENU
                    return
                }
            }
        }
        Log.w(TAG, "SIM menu not found")
    }

    // ── STEP 2: Toggle correct SIM switch by index ────────────────────────────
    private fun toggleCorrectSimSwitch(root: AccessibilityNodeInfo) {
        val allSwitches = getAllSwitches(root)
        Log.d(TAG, "Switches count: ${allSwitches.size}")

        allSwitches.forEachIndexed { i, sw ->
            val b = Rect(); sw.getBoundsInScreen(b)
            Log.d(TAG, "  Switch[$i] top=${b.top} checked=${sw.isChecked}")
        }

        val targetSwitch = allSwitches.getOrNull(targetSlot)
        if (targetSwitch == null) {
            Log.w(TAG, "Switch for slot $targetSlot not found")
            showResultNotification(targetSlot, wantOff, false, "SIM switch not found on screen")
            done()
            return
        }

        val isOn = targetSwitch.isChecked
        Log.d(TAG, "Switch[slot=$targetSlot] isOn=$isOn wantOff=$wantOff")

        when {
            wantOff && isOn -> {
                val clicked = targetSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked to turn OFF: $clicked")
                if (clicked) step = Step.CONFIRM_POPUP
            }
            !wantOff && !isOn -> {
                val clicked = targetSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked to turn ON: $clicked")
                if (clicked) {
                    // No confirmation for turning ON
                    showResultNotification(targetSlot, wantOff, true, null)
                    handler.postDelayed({ done() }, 500)
                }
            }
            else -> {
                val state = if (isOn) "already ON" else "already OFF"
                Log.d(TAG, "SIM $state — no action needed")
                showResultNotification(targetSlot, wantOff, true, "Was $state")
                done()
            }
        }
    }

    // ── STEP 3: Click confirmation popup ─────────────────────────────────────
    private fun clickConfirmation(root: AccessibilityNodeInfo) {
        listOf("Turn off", "TURN OFF", "OK", "Ok", "Yes", "YES").forEach { text ->
            root.findAccessibilityNodeInfosByText(text).forEach { node ->
                val target = if (node.isClickable) node
                             else node.parent?.takeIf { it.isClickable } ?: return@forEach
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Confirmation '$text': $clicked")
                if (clicked) {
                    Log.d(TAG, "✅ SIM slot $targetSlot toggled!")
                    showResultNotification(targetSlot, wantOff, true, null)
                    handler.postDelayed({ done() }, 600)
                    return
                }
            }
        }
        Log.w(TAG, "Confirmation button not found yet")
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SIM Scheduler",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "SIM toggle notifications"
                enableVibration(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    // Shows "Working..." notification while Settings is being automated
    private fun showWorkingNotification(simSlot: Int, turnOff: Boolean) {
        val simLabel = "SIM ${simSlot + 1}"
        val action   = if (turnOff) "Turning OFF" else "Turning ON"

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⏳ $action $simLabel...")
            .setContentText("Please wait — automating Settings")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)         // Can't be dismissed while working
            .setAutoCancel(false)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    // Shows result notification after toggle completes
    private fun showResultNotification(
        simSlot: Int,
        turnOff: Boolean,
        success: Boolean,
        reason: String?
    ) {
        val simLabel = "SIM ${simSlot + 1}"
        val action   = if (turnOff) "OFF" else "ON"

        val title = if (success) {
            "✅ $simLabel turned $action"
        } else {
            "❌ Failed to turn $simLabel $action"
        }

        val body = when {
            success && reason != null -> reason
            success                   -> "$simLabel is now $action"
            reason != null            -> "Reason: $reason"
            else                      -> "Please try again"
        }

        // Tap notification → open app
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (success) android.R.drawable.ic_dialog_info
                else         android.R.drawable.ic_dialog_alert
            )
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    // ── Open main Settings ────────────────────────────────────────────────────
    private fun openMainSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            })
            Log.d(TAG, "Opened main Settings")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open Settings: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getAllSwitches(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        collectSwitches(root, result)
        result.sortBy { node ->
            val b = Rect(); node.getBoundsInScreen(b); b.top
        }
        return result
    }

    private fun collectSwitches(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val cls = node.className?.toString() ?: ""
        if (node.isCheckable || cls.contains("Switch") ||
            cls.contains("Toggle") || cls.contains("CompoundButton")) {
            result.add(node)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectSwitches(it, result) }
    }

    private fun getTexts(root: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        collectTexts(root, result)
        return result
    }

    private fun collectTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectTexts(it, result) }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(6) { if (n?.isClickable == true) return n; n = n?.parent }
        return null
    }

    private fun done() {
        Log.d(TAG, "Done — closing Settings")
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 350)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 700)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1050)
        active = false
        ScheduleRepository.clearPendingAction(applicationContext)
    }
}
