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

        // Called by DiagnosticActivity to just dump screen info
        var diagnosticMode = false

        fun triggerSimToggle(simName: String, turnOff: Boolean) {
            instance?.performSimToggle(simName, turnOff)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var targetSimName: String? = null
    private var wantOff = false
    private var active = false
    private var screenDumpCount = 0

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
        Log.d(TAG, "✅ Connected")
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); instance = null }

    fun performSimToggle(simName: String, turnOff: Boolean) {
        targetSimName = simName
        wantOff = turnOff
        active = true
        screenDumpCount = 0
        Log.d(TAG, "▶ Start: target='$simName' wantOff=$turnOff")
        openSimSettings()
        handler.postDelayed({ if (active) { Log.w(TAG, "Timeout"); reset() } }, 30_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.settings") return

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (diagnosticMode) {
                dumpScreenFull()
            } else if (active) {
                handleScreen()
            }
        }, 800)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FULL SCREEN DUMP — logs every single node with all properties
    // ─────────────────────────────────────────────────────────────────────────
    private fun dumpScreenFull() {
        val root = rootInActiveWindow ?: return
        screenDumpCount++
        Log.d(TAG, "════════ SCREEN DUMP #$screenDumpCount ════════")
        dumpNode(root, 0)
        Log.d(TAG, "════════ END DUMP ════════")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val pad = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val txt = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        val flags = buildString {
            if (node.isClickable)  append("CLICK ")
            if (node.isCheckable)  append("CHECK ")
            if (node.isChecked)    append("CHECKED ")
            if (node.isEnabled)    append("ENABLED ")
            if (node.isFocusable)  append("FOCUS ")
        }
        Log.d(TAG, "$pad[$cls] txt='$txt' desc='$desc' id='$id' $flags")
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, depth + 1) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN HANDLER — decides what to do based on screen content
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleScreen() {
        val root = rootInActiveWindow ?: return
        val target = targetSimName ?: return

        // Collect all text visible on screen
        val allTexts = mutableListOf<String>()
        collectAllText(root, allTexts)
        Log.d(TAG, "Screen texts: $allTexts")

        // ── Case 1: Confirmation popup ("Turn off Jio?") ──────────────────────
        val hasConfirmPopup = allTexts.any {
            it.contains("Turn off", ignoreCase = true) && it.length < 30
        }
        if (hasConfirmPopup) {
            Log.d(TAG, "→ Confirmation popup detected")
            clickConfirmButton(root)
            return
        }

        // ── Case 2: On SIM detail page ("Use this SIM" visible) ──────────────
        val hasUseThisSim = allTexts.any { it.equals("Use this SIM", ignoreCase = true) }
        if (hasUseThisSim) {
            Log.d(TAG, "→ SIM detail page detected")

            // CRITICAL: Check if THIS page is for the TARGET SIM
            // Page title is usually the SIM name displayed prominently
            val pageHasTargetSim = allTexts.any { text ->
                text.trim().equals(target.trim(), ignoreCase = true)
            }

            Log.d(TAG, "→ Page has target '$target': $pageHasTargetSim")

            if (pageHasTargetSim) {
                // ✅ Correct SIM page — click toggle
                clickUseThisSimToggle(root, target)
            } else {
                // ❌ Wrong SIM page — go back
                Log.w(TAG, "→ WRONG SIM page! Going back...")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            return
        }

        // ── Case 3: On SIM list page — find and tap correct SIM ──────────────
        val onSimListPage = allTexts.any {
            it.contains("SIM cards", ignoreCase = true) ||
            it.contains("SIMs & mobile", ignoreCase = true) ||
            it.contains("Mobile network", ignoreCase = true)
        }

        if (onSimListPage || allTexts.contains(target)) {
            Log.d(TAG, "→ SIM list page detected — tapping '$target'")
            tapSimRowByName(root, target)
            return
        }

        Log.w(TAG, "→ Unknown screen — reopening SIM settings")
        openSimSettings()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAP SIM ROW — finds exact SIM name in list and taps it
    // ─────────────────────────────────────────────────────────────────────────
    private fun tapSimRowByName(root: AccessibilityNodeInfo, simName: String) {
        // Try exact match first
        val nodes = root.findAccessibilityNodeInfosByText(simName)
        Log.d(TAG, "Nodes found for '$simName': ${nodes.size}")

        for (node in nodes) {
            val nodeText = node.text?.toString()?.trim() ?: ""
            Log.d(TAG, "  Node text='$nodeText' class=${node.className} clickable=${node.isClickable}")

            // Find clickable parent to tap the whole row
            val clickable = findClickableParent(node) ?: node
            val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "  Tapped: $clicked")
            if (clicked) return
        }

        Log.w(TAG, "Could not tap '$simName' row")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLICK "Use this SIM" toggle
    // ─────────────────────────────────────────────────────────────────────────
    private fun clickUseThisSimToggle(root: AccessibilityNodeInfo, simName: String) {
        // Find all switches on screen
        val switches = mutableListOf<AccessibilityNodeInfo>()
        findAllSwitches(root, switches)
        Log.d(TAG, "Switches on page: ${switches.size}")

        switches.forEach { sw ->
            Log.d(TAG, "  Switch: class=${sw.className} checked=${sw.isChecked} clickable=${sw.isClickable}")
        }

        // On SIM detail page there should be only 1 switch = "Use this SIM"
        val switch = switches.firstOrNull()
        if (switch != null) {
            val isOn = switch.isChecked
            Log.d(TAG, "Switch isOn=$isOn wantOff=$wantOff")

            when {
                wantOff && isOn -> {
                    Log.d(TAG, "Clicking to turn OFF '$simName'")
                    val clicked = switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Click result: $clicked")
                    // Confirmation popup will appear — handled in next event
                }
                !wantOff && !isOn -> {
                    Log.d(TAG, "Clicking to turn ON '$simName'")
                    val clicked = switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Click result: $clicked")
                    if (clicked) handler.postDelayed({ reset() }, 500)
                }
                else -> {
                    Log.d(TAG, "'$simName' already in desired state")
                    reset()
                }
            }
        } else {
            // No switch found — try clicking "Use this SIM" text row directly
            Log.w(TAG, "No switch found — trying to click row")
            val useSimNodes = root.findAccessibilityNodeInfosByText("Use this SIM")
            useSimNodes.firstOrNull()?.let { node ->
                findClickableParent(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLICK confirmation "Turn off" button
    // ─────────────────────────────────────────────────────────────────────────
    private fun clickConfirmButton(root: AccessibilityNodeInfo) {
        val texts = listOf("Turn off", "TURN OFF", "OK", "Ok", "Yes", "YES", "Confirm")
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                val target = if (node.isClickable) node
                             else node.parent?.takeIf { it.isClickable } ?: continue
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Confirm '$text': clicked=$clicked")
                if (clicked) {
                    Log.d(TAG, "✅ Done! SIM toggled successfully")
                    handler.postDelayed({ reset() }, 600)
                    return
                }
            }
        }
        Log.w(TAG, "Confirm button not found yet")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OPEN SIM SETTINGS
    // ─────────────────────────────────────────────────────────────────────────
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
                Log.w(TAG, "Failed $action: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private fun collectAllText(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectAllText(it, result) }
    }

    private fun findAllSwitches(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val cls = node.className?.toString() ?: ""
        if (node.isCheckable || cls.contains("Switch") || cls.contains("Toggle") || cls.contains("CompoundButton")) {
            result.add(node)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { findAllSwitches(it, result) }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(6) {
            if (n?.isClickable == true) return n
            n = n?.parent
        }
        return null
    }

    private fun reset() {
        Log.d(TAG, "Resetting — closing Settings")
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 350)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 700)
        active = false
        targetSimName = null
        screenDumpCount = 0
        ScheduleRepository.clearPendingAction(applicationContext)
    }
}
