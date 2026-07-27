package com.simscheduler.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.simscheduler.data.ScheduleRepository

class SimAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SimSvc"
        var instance: SimAccessibilityService? = null

        fun triggerSimToggle(simSlot: Int, turnOff: Boolean) {
            instance?.performSimToggle(simSlot, turnOff)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var targetSlot = 0       // 0 = SIM1, 1 = SIM2
    private var wantOff = false
    private var active = false

    private enum class Step {
        WAITING_FOR_SIM_LIST,
        WAITING_FOR_SIM_DETAIL,
        WAITING_FOR_CONFIRMATION
    }
    private var step = Step.WAITING_FOR_SIM_LIST

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
        Log.d(TAG, "✅ Service connected")
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); instance = null }

    // ── Entry point ───────────────────────────────────────────────────────────
    fun performSimToggle(simSlot: Int, turnOff: Boolean) {
        targetSlot = simSlot
        wantOff = turnOff
        active = true
        step = Step.WAITING_FOR_SIM_LIST

        Log.d(TAG, "▶ Request: slot=$simSlot wantOff=$turnOff")

        openSimSettings()

        // Timeout after 30 seconds
        handler.postDelayed({
            if (active) {
                Log.w(TAG, "⏰ Timeout")
                finish()
            }
        }, 30_000)
    }

    // ── React to screen changes ───────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!active) return
        if (event.packageName?.toString() != "com.android.settings") return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ handleScreen() }, 700)
    }

    // ── Main screen handler ───────────────────────────────────────────────────
    private fun handleScreen() {
        if (!active) return
        val root = rootInActiveWindow ?: return

        // Collect all text on screen
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        Log.d(TAG, "Step=$step texts=$texts")

        when (step) {
            Step.WAITING_FOR_SIM_LIST    -> findAndTapSimByIndex(root)
            Step.WAITING_FOR_SIM_DETAIL  -> toggleUseThisSim(root)
            Step.WAITING_FOR_CONFIRMATION -> clickConfirmation(root)
        }
    }

    // ── STEP 1: Find correct SIM by INDEX in the list ─────────────────────────
    // This is the KEY change — we use POSITION not name
    private fun findAndTapSimByIndex(root: AccessibilityNodeInfo) {

        // Check if already on SIM detail page
        if (isOnSimDetailPage(root)) {
            Log.d(TAG, "Already on detail page")
            step = Step.WAITING_FOR_SIM_DETAIL
            toggleUseThisSim(root)
            return
        }

        // Find all SIM toggle rows on the list page
        // Each SIM row contains a Switch/Toggle
        val simRows = findSimRows(root)
        Log.d(TAG, "Found ${simRows.size} SIM rows on list")

        if (simRows.isEmpty()) {
            Log.w(TAG, "No SIM rows found — retrying")
            return
        }

        // Pick row by slot index
        // Slot 0 = first row = SIM 1
        // Slot 1 = second row = SIM 2
        val targetRow = simRows.getOrNull(targetSlot)
        if (targetRow == null) {
            Log.w(TAG, "Row for slot $targetSlot not found in ${simRows.size} rows")
            return
        }

        Log.d(TAG, "Tapping SIM row at index $targetSlot")
        val clicked = targetRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "Row tap result: $clicked")

        if (clicked) {
            step = Step.WAITING_FOR_SIM_DETAIL
        }
    }

    // ── Find all SIM rows on the list page ───────────────────────────────────
    // A SIM row is a clickable container that has a Switch inside it
    private fun findSimRows(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val rows = mutableListOf<AccessibilityNodeInfo>()
        findSimRowsRecursive(root, rows)

        // Sort by vertical position (top to bottom = SIM1 first, SIM2 second)
        rows.sortBy { node ->
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            bounds.top
        }

        Log.d(TAG, "SIM rows found and sorted by position:")
        rows.forEachIndexed { i, row ->
            val bounds = android.graphics.Rect()
            row.getBoundsInScreen(bounds)
            Log.d(TAG, "  Row $i: top=${bounds.top} class=${row.className}")
        }

        return rows
    }

    private fun findSimRowsRecursive(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        // A SIM row = clickable container that contains a Switch child
        if (node.isClickable && containsSwitch(node)) {
            // Make sure it's not too small (avoid tiny buttons)
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val height = bounds.height()
            val width  = bounds.width()

            if (height > 80 && width > 400) {
                result.add(node)
                return // Don't go deeper into this node
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findSimRowsRecursive(it, result) }
        }
    }

    // ── Check if a node contains a Switch anywhere inside it ──────────────────
    private fun containsSwitch(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: ""
        if (node.isCheckable ||
            cls.contains("Switch") ||
            cls.contains("Toggle") ||
            cls.contains("CompoundButton")) return true

        for (i in 0 until node.childCount) {
            if (containsSwitch(node.getChild(i) ?: continue)) return true
        }
        return false
    }

    // ── STEP 2: On SIM detail page — click "Use this SIM" toggle ─────────────
    private fun toggleUseThisSim(root: AccessibilityNodeInfo) {
        Log.d(TAG, "Looking for Use this SIM toggle")

        // Find the switch on this page
        val switch = findFirstSwitch(root)
        if (switch == null) {
            Log.w(TAG, "No switch found on detail page")
            return
        }

        val isOn = switch.isChecked
        Log.d(TAG, "Switch isOn=$isOn wantOff=$wantOff")

        when {
            wantOff && isOn -> {
                // Turn OFF
                val clicked = switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked to turn OFF: $clicked")
                if (clicked) step = Step.WAITING_FOR_CONFIRMATION
            }
            !wantOff && !isOn -> {
                // Turn ON — no confirmation needed
                val clicked = switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked to turn ON: $clicked")
                if (clicked) handler.postDelayed({ finish() }, 500)
            }
            else -> {
                // Already in desired state
                Log.d(TAG, "Already in desired state")
                finish()
            }
        }
    }

    // ── STEP 3: Click confirmation "Turn off" button ──────────────────────────
    private fun clickConfirmation(root: AccessibilityNodeInfo) {
        Log.d(TAG, "Looking for confirmation button")

        listOf("Turn off", "TURN OFF", "OK", "Ok", "Yes", "YES", "Confirm").forEach { text ->
            root.findAccessibilityNodeInfosByText(text).forEach { node ->
                val target = if (node.isClickable) node
                             else node.parent?.takeIf { it.isClickable } ?: return@forEach
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Confirmation '$text': $clicked")
                if (clicked) {
                    Log.d(TAG, "✅ SIM slot $targetSlot toggled successfully!")
                    handler.postDelayed({ finish() }, 600)
                    return
                }
            }
        }
        Log.w(TAG, "Confirmation button not found yet")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isOnSimDetailPage(root: AccessibilityNodeInfo): Boolean {
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        return texts.any { it.contains("Use this SIM", ignoreCase = true) }
    }

    private fun findFirstSwitch(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        if (node.isCheckable ||
            cls.contains("Switch") ||
            cls.contains("Toggle") ||
            cls.contains("CompoundButton")) return node

        for (i in 0 until node.childCount) {
            val found = findFirstSwitch(node.getChild(i) ?: continue)
            if (found != null) return found
        }
        return null
    }

    private fun collectTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, result) }
        }
    }

    private fun openSimSettings() {
        listOf(
            "android.settings.NETWORK_OPERATOR_SETTINGS",
            android.provider.Settings.ACTION_WIRELESS_SETTINGS
        ).forEach { action ->
            try {
                startActivity(Intent(action).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                })
                Log.d(TAG, "Opened: $action")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed: $action")
            }
        }
    }

    private fun finish() {
        Log.d(TAG, "Done — closing Settings")
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 350)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 700)
        active = false
        ScheduleRepository.clearPendingAction(applicationContext)
    }
}
