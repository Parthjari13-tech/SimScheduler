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
        private const val TAG = "SimAccessibility"
        var instance: SimAccessibilityService? = null

        // Called by AlarmReceiver to trigger a SIM toggle
        fun triggerSimToggle(simName: String, turnOff: Boolean) {
            instance?.performSimToggle(simName, turnOff)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSimName: String? = null
    private var pendingTurnOff: Boolean = false
    private var isWaitingForSettings = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service connected")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = arrayOf("com.android.settings")
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isWaitingForSettings) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName != "com.android.settings") return

        // Settings screen is now visible — try to find and click the SIM toggle
        handler.postDelayed({
            attemptToggle()
        }, 800) // small delay for UI to fully render
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun performSimToggle(simName: String, turnOff: Boolean) {
        pendingSimName = simName
        pendingTurnOff = turnOff
        isWaitingForSettings = true

        Log.d(TAG, "Will toggle $simName → turnOff=$turnOff")

        // Open SIM settings screen
        openSimSettings()

        // Timeout fallback — clear state after 15 seconds
        handler.postDelayed({
            if (isWaitingForSettings) {
                Log.w(TAG, "Timeout waiting for Settings screen")
                isWaitingForSettings = false
                ScheduleRepository.clearPendingAction(applicationContext)
            }
        }, 15000)
    }

    private fun openSimSettings() {
        try {
            // Try direct SIM settings intent (works on most devices)
            val intent = Intent("android.settings.NETWORK_OPERATOR_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: open general wireless settings
            try {
                val intent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Could not open SIM settings: ${e2.message}")
            }
        }
    }

    private fun attemptToggle(): Boolean {
        val simName = pendingSimName ?: return false
        val root = rootInActiveWindow ?: return false

        Log.d(TAG, "Searching for SIM toggle: $simName")

        // Strategy 1: Find by SIM name text, then find nearby switch
        val targetNode = findSimToggleNode(root, simName)

        if (targetNode != null) {
            val clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Clicked toggle for $simName: $clicked")

            if (clicked) {
                isWaitingForSettings = false
                pendingSimName = null
                ScheduleRepository.clearPendingAction(applicationContext)

                // Close Settings after short delay
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 1000)
                return true
            }
        }

        Log.w(TAG, "Could not find toggle for $simName — will retry")
        // Retry once more after 1 second
        handler.postDelayed({
            if (isWaitingForSettings) {
                val retryResult = attemptToggle()
                if (!retryResult) {
                    isWaitingForSettings = false
                    ScheduleRepository.clearPendingAction(applicationContext)
                }
            }
        }, 1000)
        return false
    }

    private fun findSimToggleNode(root: AccessibilityNodeInfo, simName: String): AccessibilityNodeInfo? {
        // Find the text node containing the SIM name
        val simNameNodes = root.findAccessibilityNodeInfosByText(simName)

        for (nameNode in simNameNodes) {
            // Walk up to find the parent row/container
            var parent = nameNode.parent
            var depth = 0
            while (parent != null && depth < 5) {
                // Look for a Switch or CheckBox sibling within this container
                val switchNode = findSwitchInContainer(parent)
                if (switchNode != null) {
                    Log.d(TAG, "Found switch for $simName at depth $depth")
                    return switchNode
                }
                parent = parent.parent
                depth++
            }
        }

        // Strategy 2: Find all switches and match by position/index
        return findSwitchBySimSlot(root, simName)
    }

    private fun findSwitchInContainer(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until container.childCount) {
            val child = container.getChild(i) ?: continue
            val className = child.className?.toString() ?: ""

            if (className.contains("Switch") ||
                className.contains("ToggleButton") ||
                child.isCheckable) {
                return child
            }

            // Recurse into children
            val found = findSwitchInContainer(child)
            if (found != null) return found
        }
        return null
    }

    private fun findSwitchBySimSlot(root: AccessibilityNodeInfo, simName: String): AccessibilityNodeInfo? {
        // Collect all SIM-related switches on screen
        val allSwitches = mutableListOf<AccessibilityNodeInfo>()
        collectSwitches(root, allSwitches)

        // If we find exactly 2 switches (for 2 SIMs), pick the right one
        // by checking which SIM name appears closest above each switch
        Log.d(TAG, "Found ${allSwitches.size} switches on screen")

        // Try to match switch with sim name by proximity
        val simNodes = root.findAccessibilityNodeInfosByText(simName)
        if (simNodes.isNotEmpty() && allSwitches.isNotEmpty()) {
            val simNode = simNodes[0]
            return allSwitches.minByOrNull { switch ->
                val simBounds = android.graphics.Rect()
                val switchBounds = android.graphics.Rect()
                simNode.getBoundsInScreen(simBounds)
                switch.getBoundsInScreen(switchBounds)
                Math.abs(simBounds.centerY() - switchBounds.centerY())
            }
        }
        return null
    }

    private fun collectSwitches(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val className = node.className?.toString() ?: ""
        if (className.contains("Switch") || (node.isCheckable && node.isClickable)) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectSwitches(it, result) }
        }
    }
}
