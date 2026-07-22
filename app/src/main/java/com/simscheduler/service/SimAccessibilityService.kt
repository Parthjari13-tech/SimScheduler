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

    // State machine
    private var pendingSimName: String? = null
    private var pendingTurnOff: Boolean = false
    private var state: State = State.IDLE

    private enum class State {
        IDLE,
        WAITING_FOR_SIM_LIST,      // Waiting for main SIM list page
        WAITING_FOR_SIM_DETAIL,    // Waiting for individual SIM detail page
        WAITING_FOR_CONFIRMATION,  // Waiting for "Turn off?" popup
        DONE
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Accessibility Service connected")

        val info = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
        }
        serviceInfo = info
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); instance = null }

    // ── Called by AlarmReceiver ───────────────────────────────────────────────
    fun performSimToggle(simName: String, turnOff: Boolean) {
        if (state != State.IDLE) {
            Log.w(TAG, "Already running — ignoring new request")
            return
        }

        pendingSimName = simName
        pendingTurnOff = turnOff
        state = State.WAITING_FOR_SIM_LIST

        Log.d(TAG, "🔄 Starting: $simName → ${if (turnOff) "OFF" else "ON"}")

        // Open SIM & mobile network settings
        openSimSettings()

        // Global timeout — give up after 30 seconds
        handler.postDelayed({
            if (state != State.IDLE && state != State.DONE) {
                Log.w(TAG, "⏰ Timeout — force closing")
                finishAndClose()
            }
        }, 30_000)
    }

    // ── React to screen changes ───────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (state == State.IDLE || state == State.DONE) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.settings") return

        // Debounce — wait for screen to fully render
        handler.removeCallbacksAndMessages("screen_ready")
        handler.postDelayed({
            when (state) {
                State.WAITING_FOR_SIM_LIST    -> handleSimListScreen()
                State.WAITING_FOR_SIM_DETAIL  -> handleSimDetailScreen()
                State.WAITING_FOR_CONFIRMATION -> handleConfirmationDialog()
                else -> {}
            }
        }, 600)
    }

    // ── STEP 1: On SIM list screen — find and tap the right SIM ──────────────
    private fun handleSimListScreen() {
        val root = rootInActiveWindow ?: return
        val simName = pendingSimName ?: return

        Log.d(TAG, "📋 Looking at SIM list screen for: $simName")

        // Check if we're already on the SIM detail page (has "Use this SIM" text)
        val useSimNodes = root.findAccessibilityNodeInfosByText("Use this SIM")
        if (useSimNodes.isNotEmpty()) {
            Log.d(TAG, "Already on SIM detail page")
            state = State.WAITING_FOR_SIM_DETAIL
            handleSimDetailScreen()
            return
        }

        // Find the SIM name on the list and tap it to open its detail page
        val simNodes = root.findAccessibilityNodeInfosByText(simName)
        if (simNodes.isNotEmpty()) {
            val simNode = simNodes[0]
            Log.d(TAG, "Found '$simName' on list — tapping to open detail page")

            // Try clicking the node itself or its parent row
            var clicked = simNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                clicked = simNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            if (!clicked) {
                clicked = simNode.parent?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }

            if (clicked) {
                state = State.WAITING_FOR_SIM_DETAIL
                Log.d(TAG, "✅ Tapped SIM row — waiting for detail page")
            } else {
                Log.w(TAG, "❌ Could not tap SIM row — retrying")
                handler.postDelayed({ handleSimListScreen() }, 800)
            }
        } else {
            Log.w(TAG, "SIM '$simName' not found on screen — retrying")
            handler.postDelayed({ handleSimListScreen() }, 1000)
        }
    }

    // ── STEP 2: On SIM detail screen — click "Use this SIM" toggle ───────────
    private fun handleSimDetailScreen() {
        val root = rootInActiveWindow ?: return

        Log.d(TAG, "📱 On SIM detail screen — looking for 'Use this SIM' toggle")

        // Find "Use this SIM" text
        val useSimTexts = listOf("Use this SIM", "Use this sim", "USE THIS SIM")
        for (text in useSimTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                val useSimNode = nodes[0]
                Log.d(TAG, "Found 'Use this SIM' text")

                // Find the toggle/switch near this text
                val toggle = findNearbyToggle(useSimNode)

                if (toggle != null) {
                    val isCurrentlyChecked = toggle.isChecked
                    Log.d(TAG, "Toggle state: isChecked=$isCurrentlyChecked, wantOff=$pendingTurnOff")

                    when {
                        // Want OFF and currently ON → click to turn off
                        pendingTurnOff && isCurrentlyChecked -> {
                            Log.d(TAG, "Clicking toggle to turn OFF")
                            val clicked = toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (!clicked) {
                                // Try clicking the whole row instead
                                useSimNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                            state = State.WAITING_FOR_CONFIRMATION
                            Log.d(TAG, "✅ Toggle clicked — waiting for confirmation popup")
                        }

                        // Want ON and currently OFF → click to turn on
                        !pendingTurnOff && !isCurrentlyChecked -> {
                            Log.d(TAG, "Clicking toggle to turn ON")
                            val clicked = toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (!clicked) {
                                useSimNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                            // No confirmation needed for turning ON
                            Log.d(TAG, "✅ SIM turned ON — closing")
                            handler.postDelayed({ finishAndClose() }, 800)
                        }

                        // Already in desired state
                        else -> {
                            Log.d(TAG, "SIM already in desired state — nothing to do")
                            finishAndClose()
                        }
                    }
                    return
                } else {
                    // Toggle not found near text — try clicking the whole row
                    Log.w(TAG, "Toggle not found near text — clicking row directly")
                    val clicked = useSimNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (clicked) {
                        state = State.WAITING_FOR_CONFIRMATION
                    }
                }
                return
            }
        }

        Log.w(TAG, "'Use this SIM' not found — still waiting")
    }

    // ── STEP 3: Auto-click the "Turn off" confirmation button ─────────────────
    private fun handleConfirmationDialog() {
        val root = rootInActiveWindow ?: return

        Log.d(TAG, "🔔 Looking for confirmation dialog")

        // All possible button texts in the confirmation dialog
        val confirmButtonTexts = listOf(
            "Turn off", "TURN OFF", "turn off",
            "OK", "Ok", "YES", "Yes",
            "Confirm", "Disable", "DISABLE"
        )

        for (text in confirmButtonTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                // Make sure this is inside a dialog (not just any button)
                val isInDialog = isInsideDialog(node)
                val target = when {
                    node.isClickable -> node
                    node.parent?.isClickable == true -> node.parent
                    else -> null
                }

                if (target != null) {
                    val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d(TAG, "✅ Clicked confirmation button: '$text'")
                        state = State.DONE
                        handler.postDelayed({ finishAndClose() }, 800)
                        return
                    }
                }
            }
        }

        Log.w(TAG, "Confirmation dialog not found yet — retrying")
        // It will be retried on next accessibility event
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findNearbyToggle(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Walk up 5 levels looking for a Switch inside any container
        var current = node
        repeat(5) {
            val switch = findSwitchInSubtree(current)
            if (switch != null) return switch
            current = current.parent ?: return null
        }
        return null
    }

    private fun findSwitchInSubtree(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = root.className?.toString() ?: ""
        if (root.isCheckable || className.contains("Switch") || className.contains("Toggle")) {
            return root
        }
        for (i in 0 until root.childCount) {
            val found = findSwitchInSubtree(root.getChild(i) ?: continue)
            if (found != null) return found
        }
        return null
    }

    private fun isInsideDialog(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val className = current?.className?.toString() ?: ""
            if (className.contains("Dialog") || className.contains("Alert")) return true
            current = current?.parent
        }
        return true // Assume it's valid even if not found in a dialog
    }

    private fun openSimSettings() {
        val intentsToTry = listOf(
            "android.settings.NETWORK_OPERATOR_SETTINGS",
            android.provider.Settings.ACTION_WIRELESS_SETTINGS
        )

        for (action in intentsToTry) {
            try {
                val intent = Intent(action).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                startActivity(intent)
                Log.d(TAG, "📂 Opened settings with action: $action")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed with $action: ${e.message}")
            }
        }
    }

    private fun finishAndClose() {
        Log.d(TAG, "🏁 Finishing — pressing back to close Settings")
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
            }, 300)
        }, 300)

        // Reset state
        state = State.IDLE
        pendingSimName = null
        ScheduleRepository.clearPendingAction(applicationContext)
        Log.d(TAG, "✅ All done — state reset to IDLE")
    }
}
