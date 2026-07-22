package com.simscheduler.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
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
    private var isWaitingForSimPage = false
    private var isWaitingForConfirmation = false
    private var toggleClicked = false
    private var retryCount = 0

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
        if (packageName != "com.android.settings") return

        if (isWaitingForConfirmation) {
            // Try to auto-click the confirmation dialog
            handler.postDelayed({ handleConfirmationDialog() }, 400)
            return
        }

        if (isWaitingForSimPage && !toggleClicked) {
            handler.postDelayed({ attemptClickUseThisSimToggle() }, 700)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ── Entry point called by AlarmReceiver ───────────────────────────────────
    fun performSimToggle(simName: String, turnOff: Boolean) {
        pendingSimName = simName
        pendingTurnOff = turnOff
        isWaitingForSimPage = true
        isWaitingForConfirmation = false
        toggleClicked = false
        retryCount = 0

        Log.d(TAG, "Starting toggle: $simName turnOff=$turnOff")

        // Open the specific SIM detail page directly
        openSimDetailPage(simName)

        // Safety timeout — clean up after 25 seconds no matter what
        handler.postDelayed({
            if (isWaitingForSimPage || isWaitingForConfirmation) {
                Log.w(TAG, "Timeout reached — cleaning up")
                closeSettingsAndReset()
            }
        }, 25000)
    }

    // ── Open the individual SIM detail page ───────────────────────────────────
    private fun openSimDetailPage(simName: String) {
        try {
            // Try to open SIM settings — this opens the SIM list or detail page
            val intent = Intent(android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            startActivity(intent)
            Log.d(TAG, "Opened SIM network settings")
        } catch (e: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot open settings: ${e2.message}")
            }
        }
    }

    // ── Click "Use this SIM" toggle on the SIM detail page ───────────────────
    private fun attemptClickUseThisSimToggle() {
        if (toggleClicked) return
        val root = rootInActiveWindow ?: return

        // Strategy 1: Find "Use this SIM" text and click its toggle
        val useSimTexts = listOf("Use this SIM", "Use this sim", "USE THIS SIM", "Use SIM")
        for (text in useSimTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                // Look for a switch/toggle in the same container
                val toggle = findToggleNearNode(node)
                if (toggle != null) {
                    // Check current state matches what we want
                    val isCurrentlyOn = toggle.isChecked
                    Log.d(TAG, "Found 'Use this SIM' toggle — isOn=$isCurrentlyOn, wantOff=$pendingTurnOff")

                    if (pendingTurnOff && isCurrentlyOn) {
                        // We want to turn OFF and it's currently ON — click it
                        val clicked = toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!clicked) {
                            // Try clicking the parent row instead
                            node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        Log.d(TAG, "Clicked to turn OFF ✅")
                        toggleClicked = true
                        isWaitingForSimPage = false
                        isWaitingForConfirmation = true
                        // Wait for confirmation dialog
                        handler.postDelayed({ retryConfirmation(5) }, 600)
                        return

                    } else if (!pendingTurnOff && !isCurrentlyOn) {
                        // We want to turn ON and it's currently OFF — click it
                        val clicked = toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!clicked) {
                            node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        Log.d(TAG, "Clicked to turn ON ✅")
                        toggleClicked = true
                        isWaitingForSimPage = false
                        isWaitingForConfirmation = false
                        // No confirmation needed for turning ON — just close
                        handler.postDelayed({ closeSettingsAndReset() }, 1000)
                        return

                    } else {
                        // SIM already in desired state — nothing to do
                        Log.d(TAG, "SIM already in desired state — skipping")
                        toggleClicked = true
                        closeSettingsAndReset()
                        return
                    }
                }
            }
        }

        // Strategy 2: If we're on the SIM list page (not detail page yet)
        // Find the SIM name and tap it to go to detail page
        if (retryCount < 2) {
            val simName = pendingSimName ?: return
            val simNodes = root.findAccessibilityNodeInfosByText(simName)
            if (simNodes.isNotEmpty()) {
                // Check if there's a direct toggle next to SIM name (list view)
                val toggle = findToggleNearNode(simNodes[0])
                if (toggle != null) {
                    val isCurrentlyOn = toggle.isChecked
                    if (pendingTurnOff && isCurrentlyOn || !pendingTurnOff && !isCurrentlyOn) {
                        toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        toggleClicked = true
                        isWaitingForSimPage = false
                        isWaitingForConfirmation = pendingTurnOff
                        if (pendingTurnOff) {
                            handler.postDelayed({ retryConfirmation(5) }, 600)
                        } else {
                            handler.postDelayed({ closeSettingsAndReset() }, 800)
                        }
                    } else {
                        closeSettingsAndReset()
                    }
                } else {
                    // Tap the SIM name to open its detail page
                    simNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    retryCount++
                }
            }
        }
    }

    // ── Auto-click the "Turn off" confirmation button ─────────────────────────
    private fun handleConfirmationDialog(): Boolean {
        val root = rootInActiveWindow ?: return false

        // All possible confirmation button texts
        val confirmTexts = listOf(
            "Turn off", "TURN OFF", "turn off",
            "OK", "Ok", "Confirm", "Yes", "YES",
            "Disable", "DISABLE"
        )

        for (text in confirmTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                val clickTarget = if (node.isClickable) node else node.parent
                if (clickTarget != null && clickTarget.isClickable) {
                    val clicked = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d(TAG, "Auto-clicked confirmation: '$text' ✅")
                        isWaitingForConfirmation = false
                        // Close Settings silently after 1 second
                        handler.postDelayed({ closeSettingsAndReset() }, 1000)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun retryConfirmation(remaining: Int) {
        if (remaining <= 0 || !isWaitingForConfirmation) {
            closeSettingsAndReset()
            return
        }
        val handled = handleConfirmationDialog()
        if (!handled) {
            handler.postDelayed({ retryConfirmation(remaining - 1) }, 500)
        }
    }

    // ── Close Settings and clean up ───────────────────────────────────────────
    private fun closeSettingsAndReset() {
        // Press back twice to fully exit Settings
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
        }, 400)

        // Reset all state
        isWaitingForSimPage = false
        isWaitingForConfirmation = false
        toggleClicked = false
        pendingSimName = null
        retryCount = 0
        ScheduleRepository.clearPendingAction(applicationContext)
        Log.d(TAG, "Done — Settings closed, state reset")
    }

    // ── Find toggle/switch near a given node ──────────────────────────────────
    private fun findToggleNearNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Search in parent containers up to 4 levels
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            val switch = findSwitchInChildren(parent)
            if (switch != null) return switch
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun findSwitchInChildren(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until container.childCount) {
            val child = container.getChild(i) ?: continue
            val className = child.className?.toString() ?: ""
            if (className.contains("Switch") ||
                className.contains("ToggleButton") ||
                child.isCheckable) {
                return child
            }
            val found = findSwitchInChildren(child)
            if (found != null) return found
        }
        return null
    }
}
