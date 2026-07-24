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

        fun triggerSimToggle(simName: String, turnOff: Boolean) {
            instance?.performSimToggle(simName, turnOff)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var targetSimName: String? = null
    private var wantOff: Boolean = false
    private var active = false
    private var step = 0  // 0=find list, 1=tap correct SIM, 2=toggle, 3=confirm

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
        Log.d(TAG, "Service connected")
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); instance = null }

    fun performSimToggle(simName: String, turnOff: Boolean) {
        targetSimName = simName
        wantOff = turnOff
        active = true
        step = 0
        Log.d(TAG, "Starting toggle: target='$simName' wantOff=$turnOff")

        // IMPORTANT: Always open the PARENT SIM list page, not a specific SIM
        // Use ACTION_WIRELESS_SETTINGS which opens the top-level wireless page
        // Then navigate down to the correct SIM
        openParentSimListPage()

        handler.postDelayed({
            if (active) { Log.w(TAG, "Timeout"); done() }
        }, 30_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!active) return
        if (event.packageName?.toString() != "com.android.settings") return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ handleCurrentScreen() }, 800)
    }

    private fun handleCurrentScreen() {
        if (!active) return
        val root = rootInActiveWindow ?: return

        // Collect ALL visible text to understand what page we're on
        val texts = getAllTexts(root)
        Log.d(TAG, "=== Screen texts: $texts")
        Log.d(TAG, "=== Step: $step | Target: $targetSimName")

        when {
            // Confirmation dialog visible — click "Turn off"
            texts.any { it.contains("Turn off", true) && it.length < 20 } ||
            texts.any { it.equals("OK", true) } -> {
                Log.d(TAG, "Confirmation dialog detected")
                clickConfirmation(root)
            }

            // "Use this SIM" visible — we're on a SIM detail page
            texts.any { it.contains("Use this SIM", true) } -> {
                Log.d(TAG, "On SIM detail page")
                handleSimDetailPage(root, texts)
            }

            // SIM list page — both SIM names should be visible
            texts.any { it.contains("SIM cards", true) } ||
            texts.any { it.contains("SIMs & mobile", true) } ||
            (texts.any { it.contains("LycaMobile", true) } &&
             texts.any { it.contains("Jio", true) }) -> {
                Log.d(TAG, "On SIM list page")
                tapCorrectSimRow(root)
            }

            else -> {
                Log.w(TAG, "Unknown screen — navigating to SIM list")
                openParentSimListPage()
            }
        }
    }

    // ── On SIM detail page — verify it's the RIGHT SIM before touching ────────
    private fun handleSimDetailPage(root: AccessibilityNodeInfo, texts: List<String>) {
        val target = targetSimName ?: return

        // Check page title — the SIM name appears as page title
        val isCorrectPage = texts.any { text ->
            text.trim().equals(target.trim(), ignoreCase = true)
        }

        Log.d(TAG, "Detail page — isCorrectPage=$isCorrectPage target='$target' texts=$texts")

        if (!isCorrectPage) {
            // WRONG SIM page — go back to list
            Log.w(TAG, "WRONG SIM page! Going back to list")
            performGlobalAction(GLOBAL_ACTION_BACK)
            step = 0
            return
        }

        // Correct SIM page — find and click "Use this SIM" toggle
        Log.d(TAG, "CORRECT SIM page for '$target' — clicking toggle")
        clickUseThisSimToggle(root)
    }

    // ── Tap the correct SIM row in the list ───────────────────────────────────
    private fun tapCorrectSimRow(root: AccessibilityNodeInfo) {
        val target = targetSimName ?: return
        Log.d(TAG, "Looking for '$target' row in SIM list")

        // Find ALL nodes that contain the target SIM name
        val candidates = root.findAccessibilityNodeInfosByText(target)
        Log.d(TAG, "Found ${candidates.size} nodes with text '$target'")

        for (node in candidates) {
            val nodeText = node.text?.toString() ?: ""
            Log.d(TAG, "  Candidate: text='$nodeText' class=${node.className} clickable=${node.isClickable}")

            // Only click if it's an exact or close match — not a substring of something else
            if (nodeText.trim().equals(target.trim(), ignoreCase = true) ||
                nodeText.trim().contains(target.trim(), ignoreCase = true)) {

                // Find the clickable row containing this node
                val clickableNode = findClickableAncestor(node) ?: node
                val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Tapped '$target' row: clicked=$clicked")

                if (clicked) {
                    step = 1
                    return
                }
            }
        }

        Log.w(TAG, "Could not find '$target' row — retrying")
    }

    // ── Click "Use this SIM" toggle ───────────────────────────────────────────
    private fun clickUseThisSimToggle(root: AccessibilityNodeInfo) {
        val useSimNodes = root.findAccessibilityNodeInfosByText("Use this SIM")
        if (useSimNodes.isEmpty()) {
            Log.w(TAG, "'Use this SIM' text not found")
            return
        }

        val textNode = useSimNodes[0]
        Log.d(TAG, "Found 'Use this SIM' node")

        // Find the switch/toggle associated with this row
        val switch = findSwitchInTree(root)
        Log.d(TAG, "Switch found: ${switch != null}, isChecked: ${switch?.isChecked}")

        if (switch != null) {
            val isOn = switch.isChecked
            Log.d(TAG, "Switch isOn=$isOn wantOff=$wantOff")

            when {
                wantOff && isOn -> {
                    // Need to turn OFF
                    var clicked = switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clicked) {
                        clicked = findClickableAncestor(textNode)
                            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    }
                    Log.d(TAG, "Clicked to turn OFF: $clicked")
                }
                !wantOff && !isOn -> {
                    // Need to turn ON
                    var clicked = switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clicked) {
                        clicked = findClickableAncestor(textNode)
                            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    }
                    Log.d(TAG, "Clicked to turn ON: $clicked")
                    if (clicked) handler.postDelayed({ done() }, 500)
                }
                else -> {
                    Log.d(TAG, "Already in desired state")
                    done()
                }
            }
        } else {
            // No switch found — click the whole row
            Log.w(TAG, "No switch found — clicking row")
            findClickableAncestor(textNode)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    // ── Click "Turn off" confirmation button ──────────────────────────────────
    private fun clickConfirmation(root: AccessibilityNodeInfo) {
        val buttonTexts = listOf("Turn off", "TURN OFF", "OK", "Ok", "Yes", "Confirm")
        for (text in buttonTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                val target = if (node.isClickable) node
                             else node.parent?.takeIf { it.isClickable }
                             ?: continue
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Confirmation '$text': clicked=$clicked")
                if (clicked) {
                    Log.d(TAG, "✅ SIM turned OFF successfully!")
                    handler.postDelayed({ done() }, 600)
                    return
                }
            }
        }
        Log.w(TAG, "Confirmation button not found yet")
    }

    // ── Open the PARENT SIM list page ─────────────────────────────────────────
    // Key: We must NOT open a specific SIM's page — open the list first
    private fun openParentSimListPage() {
        val intents = listOf(
            // Opens "SIMs & mobile network" list page on most phones
            "android.settings.NETWORK_OPERATOR_SETTINGS",
            // Fallback
            android.provider.Settings.ACTION_WIRELESS_SETTINGS
        )

        for (action in intents) {
            try {
                startActivity(Intent(action).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                })
                Log.d(TAG, "Opened settings: $action")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed: $action — ${e.message}")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getAllTexts(node: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        collectTexts(node, result)
        return result.distinct()
    }

    private fun collectTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, result) }
        }
    }

    private fun findSwitchInTree(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = root.className?.toString() ?: ""
        if (root.isCheckable || cls.contains("Switch") || cls.contains("Toggle")) return root
        for (i in 0 until root.childCount) {
            val found = findSwitchInTree(root.getChild(i) ?: continue)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    private fun done() {
        Log.d(TAG, "Done — closing Settings")
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 300)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 600)
        active = false
        step = 0
        targetSimName = null
        ScheduleRepository.clearPendingAction(applicationContext)
    }
}
