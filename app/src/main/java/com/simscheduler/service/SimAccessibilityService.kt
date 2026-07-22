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

        fun triggerSimToggle(simName: String, turnOff: Boolean) {
            instance?.performSimToggle(simName, turnOff)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSimName: String? = null
    private var pendingTurnOff: Boolean = false
    private var isWaitingForSettings = false
    private var isWaitingForConfirmation = false
    private var toggleClicked = false

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
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Handle confirmation popup — "Turn off LycaMobile?" dialog
        if (isWaitingForConfirmation) {
            handler.postDelayed({
                if (handleConfirmationDialog()) {
                    Log.d(TAG, "Confirmation dialog handled ✅")
                }
            }, 500)
            return
        }

        // Handle Settings screen
        if (isWaitingForSettings && packageName == "com.android.settings") {
            handler.postDelayed({
                attemptToggle()
            }, 800)
        }
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
        isWaitingForConfirmation = false
        toggleClicked = false

        Log.d(TAG, "Will toggle $simName → turnOff=$turnOff")

        openSimSettings()

        // Timeout fallback after 20 seconds
        handler.postDelayed({
            if (isWaitingForSettings || isWaitingForConfirmation) {
                Log.w(TAG, "Timeout — clearing state")
                resetState()
                ScheduleRepository.clearPendingAction(applicationContext)
            }
        }, 20000)
    }

    // ─── Handle the "Turn off SIM?" confirmation popup ───────────────────────
    private fun handleConfirmationDialog(): Boolean {
        val root = rootInActiveWindow ?: return false

        // Look for "Turn off" button text in any visible dialog
        val confirmTexts = listOf("Turn off", "turn off", "TURN OFF", "OK", "Confirm", "Yes")

        for (text in confirmTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d(TAG, "Tapped confirmation button: '$text' ✅")
                        isWaitingForConfirmation = false
                        resetState()

                        // Close Settings after done
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            handler.postDelayed({
                                performGlobalAction(GLOBAL_ACTION_BACK)
                            }, 500)
                        }, 800)
                        return true
                    }
                }

                // Try parent if child not clickable
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d(TAG, "Tapped parent of confirmation button ✅")
                        isWaitingForConfirmation = false
                        resetState()
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }, 800)
                        return true
                    }
                }
            }
        }
        return false
    }

    // ─── Find and click the SIM toggle ───────────────────────────────────────
    private fun attemptToggle(): Boolean {
        if (toggleClicked) return true
        val simName = pendingSimName ?: return false
        val root = rootInActiveWindow ?: return false

        Log.d(TAG, "Searching for SIM toggle: $simName")

        val targetNode = findSimToggleNode(root, simName)

        if (targetNode != null) {
            val clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Clicked toggle for $simName: $clicked")

            if (clicked) {
                toggleClicked = true
                isWaitingForSettings = false
                // Now wait for confirmation popup
                isWaitingForConfirmation = true

                // Try to handle confirmation after short delay
                handler.postDelayed({
                    val handled = handleConfirmationDialog()
                    if (!handled) {
                        // Retry confirmation a few times
                        retryConfirmation(3)
                    }
                }, 600)
                return true
            }
        }

        // Retry once
        handler.postDelayed({
            if (isWaitingForSettings && !toggleClicked) {
                val retryResult = attemptToggle()
                if (!retryResult) resetState()
            }
        }, 1200)
        return false
    }

    private fun retryConfirmation(remainingRetries: Int) {
        if (remainingRetries <= 0) {
            Log.w(TAG, "Could not find confirmation dialog after retries")
            resetState()
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 500)
            return
        }

        handler.postDelayed({
            if (isWaitingForConfirmation) {
                val handled = handleConfirmationDialog()
                if (!handled) {
                    retryConfirmation(remainingRetries - 1)
                }
            }
        }, 600)
    }

    // ─── Open SIM Settings screen ─────────────────────────────────────────────
    private fun openSimSettings() {
        try {
            val intent = Intent("android.settings.NETWORK_OPERATOR_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
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

    // ─── Node finding helpers ─────────────────────────────────────────────────
    private fun findSimToggleNode(root: AccessibilityNodeInfo, simName: String): AccessibilityNodeInfo? {
        val simNameNodes = root.findAccessibilityNodeInfosByText(simName)

        for (nameNode in simNameNodes) {
            var parent = nameNode.parent
            var depth = 0
            while (parent != null && depth < 5) {
                val switchNode = findSwitchInContainer(parent)
                if (switchNode != null) return switchNode
                parent = parent.parent
                depth++
            }
        }
        return findSwitchByProximity(root, simName)
    }

    private fun findSwitchInContainer(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until container.childCount) {
            val child = container.getChild(i) ?: continue
            val className = child.className?.toString() ?: ""
            if (className.contains("Switch") || className.contains("ToggleButton") || child.isCheckable) {
                return child
            }
            val found = findSwitchInContainer(child)
            if (found != null) return found
        }
        return null
    }

    private fun findSwitchByProximity(root: AccessibilityNodeInfo, simName: String): AccessibilityNodeInfo? {
        val allSwitches = mutableListOf<AccessibilityNodeInfo>()
        collectSwitches(root, allSwitches)

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

    private fun resetState() {
        isWaitingForSettings = false
        isWaitingForConfirmation = false
        pendingSimName = null
        toggleClicked = false
        ScheduleRepository.clearPendingAction(applicationContext)
    }
}
