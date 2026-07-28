package com.simscheduler.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.simscheduler.data.ScheduleRepository

class SimAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SimSvc"
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
        OPEN_SETTINGS,          // Step 1: Waiting for main Settings page
        FIND_SIM_MENU,          // Step 2: Find and tap SIM/Network menu item
        TOGGLE_SIM_SWITCH,      // Step 3: On SIM list — tap correct switch by index
        CONFIRM_POPUP           // Step 4: Click "Turn off" confirmation
    }
    private var step = Step.OPEN_SETTINGS

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
    fun start(simSlot: Int, turnOff: Boolean) {
        targetSlot = simSlot
        wantOff    = turnOff
        active     = true
        step       = Step.OPEN_SETTINGS

        Log.d(TAG, "▶ Start: slot=$simSlot wantOff=$turnOff")

        // Open TOP LEVEL Settings — not SIM settings directly
        // This avoids the problem of intent opening SIM 1 directly
        openMainSettings()

        handler.postDelayed({
            if (active) { Log.w(TAG, "⏰ Timeout"); done() }
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

    // ── Decide what to do based on current step ───────────────────────────────
    private fun handleScreen() {
        if (!active) return
        val root = rootInActiveWindow ?: return
        val texts = getTexts(root)
        Log.d(TAG, "Step=$step | Texts=$texts")

        when (step) {
            Step.OPEN_SETTINGS,
            Step.FIND_SIM_MENU    -> findAndTapSimMenu(root, texts)
            Step.TOGGLE_SIM_SWITCH -> toggleCorrectSimSwitch(root)
            Step.CONFIRM_POPUP    -> clickConfirmation(root)
        }
    }

    // ── STEP 1 & 2: Find "SIM" menu item in Settings and tap it ──────────────
    // This navigates from main Settings → SIM list page
    // Avoids the bug of opening SIM 1 detail page directly
    private fun findAndTapSimMenu(root: AccessibilityNodeInfo, texts: List<String>) {

        // Check if we already reached the SIM list page
        // SIM list page has MULTIPLE switches (one per SIM)
        val allSwitches = getAllSwitches(root)
        Log.d(TAG, "Switches on screen: ${allSwitches.size}")

        if (allSwitches.size >= 2) {
            // We're on SIM list — has 2 switches (SIM 1 and SIM 2)
            Log.d(TAG, "✅ SIM list detected (${allSwitches.size} switches)")
            step = Step.TOGGLE_SIM_SWITCH
            toggleCorrectSimSwitch(root)
            return
        }

        if (allSwitches.size == 1) {
            // Only 1 switch — might be on SIM detail page or single SIM setting
            // Check if "SIM cards" heading is visible (list page)
            val onListPage = texts.any {
                it.contains("SIM cards", true) ||
                it.contains("SIMs & mobile", true)
            }
            if (onListPage) {
                // List page but only 1 switch found yet — wait for full render
                Log.d(TAG, "List page loading — retrying")
                handler.postDelayed({ handleScreen() }, 500)
                return
            }
        }

        // Look for SIM-related menu items to tap
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
                val node = nodes[0]
                val clickable = findClickableParent(node) ?: node
                val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Tapped menu '$keyword': clicked=$clicked")
                if (clicked) {
                    step = Step.FIND_SIM_MENU
                    return
                }
            }
        }

        Log.w(TAG, "SIM menu not found — texts: $texts")
    }

    // ── STEP 3: On SIM list — tap the CORRECT switch by position index ────────
    // SIM 1 = topmost switch on screen
    // SIM 2 = second switch from top
    // No name matching needed — purely position based
    private fun toggleCorrectSimSwitch(root: AccessibilityNodeInfo) {
        val allSwitches = getAllSwitches(root)
        Log.d(TAG, "Switches found: ${allSwitches.size}")

        allSwitches.forEachIndexed { i, sw ->
            val b = Rect()
            sw.getBoundsInScreen(b)
            Log.d(TAG, "  Switch[$i]: top=${b.top} checked=${sw.isChecked}")
        }

        if (allSwitches.isEmpty()) {
            Log.w(TAG, "No switches found — retrying")
            return
        }

        // Pick switch by slot index
        val targetSwitch = allSwitches.getOrNull(targetSlot)

        if (targetSwitch == null) {
            Log.w(TAG, "Switch for slot $targetSlot not found in ${allSwitches.size} switches")
            // If only 1 switch and targeting slot 1 — might be wrong page
            // Go back and retry
            performGlobalAction(GLOBAL_ACTION_BACK)
            step = Step.FIND_SIM_MENU
            return
        }

        val isOn = targetSwitch.isChecked
        Log.d(TAG, "Target switch[slot=$targetSlot] isOn=$isOn wantOff=$wantOff")

        when {
            wantOff && isOn -> {
                // Need to turn OFF — click the switch
                val clicked = targetSwitch.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked to turn OFF: $clicked")
                if (clicked) step = Step.CONFIRM_POPUP
            }
            !wantOff && !isOn -> {
                // Need to turn ON — click the switch
                val clicked = targetSwitch.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked to turn ON: $clicked")
                // No confirmation needed for ON
                if (clicked) handler.postDelayed({ done() }, 500)
            }
            else -> {
                // Already in desired state
                Log.d(TAG, "Already in desired state")
                done()
            }
        }
    }

    // ── STEP 4: Click "Turn off" confirmation popup ───────────────────────────
    private fun clickConfirmation(root: AccessibilityNodeInfo) {
        Log.d(TAG, "Looking for confirmation button")

        listOf("Turn off", "TURN OFF", "OK", "Ok", "Yes", "YES").forEach { text ->
            root.findAccessibilityNodeInfosByText(text).forEach { node ->
                val target = if (node.isClickable) node
                             else node.parent?.takeIf { it.isClickable }
                             ?: return@forEach
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Confirmation '$text': $clicked")
                if (clicked) {
                    Log.d(TAG, "✅ SIM slot $targetSlot toggled!")
                    handler.postDelayed({ done() }, 600)
                    return
                }
            }
        }
        Log.w(TAG, "Confirmation not found yet")
    }

    // ── Open TOP LEVEL Settings (not SIM settings) ────────────────────────────
    // This is the key fix — we start from main Settings
    // and navigate down ourselves instead of jumping to SIM page directly
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

    // ── Get ALL switches on screen sorted top to bottom ───────────────────────
    // TOP switch    = SIM 1 (slot 0)
    // SECOND switch = SIM 2 (slot 1)
    private fun getAllSwitches(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        collectSwitches(root, result)

        // Sort by Y position — topmost first
        result.sortBy { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.top
        }

        return result
    }

    private fun collectSwitches(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        val cls = node.className?.toString() ?: ""
        if (node.isCheckable ||
            cls.contains("Switch") ||
            cls.contains("Toggle") ||
            cls.contains("CompoundButton")) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectSwitches(it, result) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun getTexts(root: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        collectTexts(root, result)
        return result
    }

    private fun collectTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, result) }
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(6) {
            if (n?.isClickable == true) return n
            n = n?.parent
        }
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
