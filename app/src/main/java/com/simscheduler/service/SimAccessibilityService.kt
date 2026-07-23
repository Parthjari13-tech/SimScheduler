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
    private var isActive = false
    private var attemptCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Service connected")
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

    fun performSimToggle(simName: String, turnOff: Boolean) {
        pendingSimName = simName
        pendingTurnOff = turnOff
        isActive = true
        attemptCount = 0
        Log.d(TAG, "🔄 Request: $simName → ${if (turnOff) "OFF" else "ON"}")
        openSimSettings()

        // Timeout after 30 seconds
        handler.postDelayed({
            if (isActive) {
                Log.w(TAG, "⏰ Timeout")
                reset()
                closeSettings()
            }
        }, 30_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isActive) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.settings") return

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ processCurrentScreen() }, 800)
    }

    // ── Main logic — runs every time screen updates ───────────────────────────
    private fun processCurrentScreen() {
        if (!isActive) return
        val root = rootInActiveWindow ?: return
        attemptCount++

        Log.d(TAG, "--- Screen scan #$attemptCount ---")

        // Dump ALL nodes to log so we can see what's on screen
        dumpAllNodes(root, 0)

        // Strategy 1: Find "Use this SIM" text and click its row
        if (tryClickUseThisSim(root)) return

        // Strategy 2: Find any Switch/Toggle and click it
        if (tryClickAnySwitch(root)) return

        // Strategy 3: Find confirmation dialog buttons
        if (tryClickConfirmation(root)) return

        Log.w(TAG, "Nothing actionable found on screen yet (attempt $attemptCount)")

        // Give up after 10 attempts
        if (attemptCount >= 10) {
            Log.e(TAG, "❌ Giving up after $attemptCount attempts")
            reset()
            closeSettings()
        }
    }

    // ── Strategy 1: Click "Use this SIM" row ─────────────────────────────────
    private fun tryClickUseThisSim(root: AccessibilityNodeInfo): Boolean {
        val searchTexts = listOf(
            "Use this SIM", "Use this sim", "USE THIS SIM",
            "Enable", "enable", "Active", "active"
        )

        for (text in searchTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isEmpty()) continue

            Log.d(TAG, "Found text: '$text' — node count: ${nodes.size}")

            for (textNode in nodes) {
                // Log the node details
                Log.d(TAG, "  TextNode: class=${textNode.className} clickable=${textNode.isClickable} checkable=${textNode.isCheckable} checked=${textNode.isChecked}")

                // Find switch in same container
                val switch = findSwitchNear(textNode)
                if (switch != null) {
                    val isOn = switch.isChecked
                    Log.d(TAG, "  Switch found: isChecked=$isOn, wantOff=$pendingTurnOff")

                    if ((pendingTurnOff && isOn) || (!pendingTurnOff && !isOn)) {
                        // Need to toggle
                        Log.d(TAG, "  Clicking switch...")
                        var clicked = switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!clicked) clicked = textNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                        if (!clicked) clicked = textNode.parent?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

                        Log.d(TAG, "  Click result: $clicked")
                        if (clicked) {
                            if (pendingTurnOff) {
                                // Wait for confirmation dialog
                                Log.d(TAG, "Waiting for confirmation popup...")
                                attemptCount = 0 // Reset so confirmation gets full attempts
                            } else {
                                Log.d(TAG, "✅ SIM turned ON")
                                reset()
                                handler.postDelayed({ closeSettings() }, 500)
                            }
                            return true
                        }
                    } else {
                        Log.d(TAG, "  Already in desired state — closing")
                        reset()
                        closeSettings()
                        return true
                    }
                } else {
                    // No switch found — try clicking the row directly
                    Log.d(TAG, "  No switch found near text — clicking row")
                    val row = findClickableParent(textNode)
                    if (row != null) {
                        val clicked = row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "  Row click result: $clicked")
                        if (clicked) return true
                    }
                }
            }
        }
        return false
    }

    // ── Strategy 2: Click any switch on screen ────────────────────────────────
    private fun tryClickAnySwitch(root: AccessibilityNodeInfo): Boolean {
        val switches = mutableListOf<AccessibilityNodeInfo>()
        collectAllSwitches(root, switches)

        Log.d(TAG, "Total switches found: ${switches.size}")

        if (switches.isEmpty()) return false

        // If only 1 switch on screen — it's probably "Use this SIM"
        if (switches.size == 1) {
            val switch = switches[0]
            val isOn = switch.isChecked
            Log.d(TAG, "Single switch: isChecked=$isOn, wantOff=$pendingTurnOff class=${switch.className}")

            if ((pendingTurnOff && isOn) || (!pendingTurnOff && !isOn)) {
                val clicked = switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Single switch click: $clicked")
                if (clicked) {
                    if (!pendingTurnOff) {
                        reset()
                        handler.postDelayed({ closeSettings() }, 500)
                    }
                    return true
                }
            } else {
                Log.d(TAG, "Switch already in desired state")
                reset()
                closeSettings()
                return true
            }
        }

        return false
    }

    // ── Strategy 3: Click confirmation dialog ────────────────────────────────
    private fun tryClickConfirmation(root: AccessibilityNodeInfo): Boolean {
        val buttonTexts = listOf(
            "Turn off", "TURN OFF",
            "OK", "Ok",
            "Yes", "YES",
            "Confirm", "Disable"
        )

        for (text in buttonTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                Log.d(TAG, "Confirmation candidate: '$text' clickable=${node.isClickable}")
                val target = if (node.isClickable) node else node.parent
                if (target?.isClickable == true) {
                    val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Confirmation click '$text': $clicked")
                    if (clicked) {
                        Log.d(TAG, "✅ Confirmed! SIM turned OFF")
                        reset()
                        handler.postDelayed({ closeSettings() }, 800)
                        return true
                    }
                }
            }
        }
        return false
    }

    // ── Dump all nodes to log (for debugging) ────────────────────────────────
    private fun dumpAllNodes(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val extra = buildString {
            if (node.isClickable) append(" CLICKABLE")
            if (node.isCheckable) append(" CHECKABLE")
            if (node.isChecked) append(" CHECKED")
            if (node.isEnabled) append(" ENABLED")
        }

        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isCheckable) {
            Log.d(TAG, "$indent[$cls]$extra text='$text' desc='$desc'")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpAllNodes(it, depth + 1) }
        }
    }

    // ── Node helpers ──────────────────────────────────────────────────────────
    private fun findSwitchNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            current?.let { n ->
                val found = findSwitchInSubtree(n)
                if (found != null && found != node) return found
            }
            current = current?.parent
        }
        return null
    }

    private fun findSwitchInSubtree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        if (node.isCheckable || cls.contains("Switch") || cls.contains("Toggle") || cls.contains("CompoundButton")) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findSwitchInSubtree(node.getChild(i) ?: continue)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        repeat(5) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    private fun collectAllSwitches(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val cls = node.className?.toString() ?: ""
        if (node.isCheckable || cls.contains("Switch") || cls.contains("Toggle")) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllSwitches(it, result) }
        }
    }

    private fun openSimSettings() {
        try {
            val intent = Intent("android.settings.NETWORK_OPERATOR_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            startActivity(intent)
            Log.d(TAG, "📂 Opened SIM settings")
        } catch (e: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot open settings: ${e2.message}")
            }
        }
    }

    private fun closeSettings() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 300)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 600)
    }

    private fun reset() {
        isActive = false
        attemptCount = 0
        pendingSimName = null
        ScheduleRepository.clearPendingAction(applicationContext)
    }
}
