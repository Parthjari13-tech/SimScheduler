package com.simscheduler.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.simscheduler.service.SimAccessibilityService
import com.simscheduler.util.SimDetector

class DiagnosticActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var simNameInput: EditText
    private val logs = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "🔧 SIM Diagnostic Tool"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        })

        // ── Section 1: Auto Detected SIMs ────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "AUTO DETECTED SIMs"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 8)
        })

        val sims = SimDetector.detectSims(this)
        if (sims.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "❌ No SIMs detected — check READ_PHONE_STATE permission"
                setTextColor(0xFFFF4444.toInt())
                setPadding(0, 0, 0, 16)
            })
        } else {
            sims.forEach { sim ->
                root.addView(TextView(this).apply {
                    text = "Slot ${sim.slot} (SIM ${sim.slot + 1}):\n" +
                           "  Carrier: '${sim.carrierName}'\n" +
                           "  Number:  ${sim.phoneNumber.ifEmpty { "unknown" }}"
                    textSize = 14f
                    setBackgroundColor(0xFFE8F5E9.toInt())
                    setPadding(16, 12, 16, 12)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 8) }
                })

                // Show ALL candidate names for this SIM
                val candidates = SimDetector.getAllCandidatesForSlot(this, sim.slot)
                root.addView(TextView(this).apply {
                    text = "  All name sources for Slot ${sim.slot}:\n" +
                           candidates.entries.joinToString("\n") { (k, v) -> "    $k = '$v'" }
                    textSize = 11f
                    setTextColor(0xFF555555.toInt())
                    setTypeface(null, android.graphics.Typeface.ITALIC)
                    setPadding(0, 0, 0, 16)
                })
            }
        }

        // ── Section 2: Manual Test ────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "MANUAL TEST"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 8, 0, 8)
        })

        root.addView(TextView(this).apply {
            text = "Enter the exact SIM name shown in\nSettings → SIMs & mobile network:"
            textSize = 13f
            setPadding(0, 0, 0, 8)
        })

        simNameInput = EditText(this).apply {
            hint = "e.g. Jio  or  LycaMobile"
            textSize = 16f
            setPadding(16, 12, 16, 12)
            // Pre-fill with first detected SIM name
            sims.firstOrNull()?.let { setText(it.carrierName) }
        }
        root.addView(simNameInput)

        // Test buttons
        root.addView(Button(this).apply {
            text = "▶ TEST Turn OFF this SIM"
            setBackgroundColor(0xFFD32F2F.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { testToggle(true) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120
            ).apply { setMargins(0, 16, 0, 8) }
        })

        root.addView(Button(this).apply {
            text = "▶ TEST Turn ON this SIM"
            setBackgroundColor(0xFF388E3C.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { testToggle(false) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120
            ).apply { setMargins(0, 0, 0, 8) }
        })

        // Open Settings button
        root.addView(Button(this).apply {
            text = "📱 Open SIM Settings (to check exact name)"
            setOnClickListener { openSimSettings() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 100
            ).apply { setMargins(0, 0, 0, 16) }
        })

        // ── Section 3: Log Output ─────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "LOG OUTPUT"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 8)
        })

        logView = TextView(this).apply {
            text = "Tap a test button to see results here...\n" +
                   "Also check Logcat → filter by 'SimDetector' or 'SimSvc'"
            textSize = 12f
            setTextColor(0xFF00FF00.toInt())
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(16, 16, 16, 16)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300
            )
            addView(logView)
        }
        root.addView(scroll)

        val mainScroll = ScrollView(this)
        mainScroll.addView(root)
        setContentView(mainScroll)

        // Log detected SIMs immediately
        appendLog("=== SIM Detection Results ===")
        sims.forEach { sim ->
            appendLog("Slot ${sim.slot}: '${sim.carrierName}' ${sim.phoneNumber}")
        }
        appendLog("============================")
    }

    private fun testToggle(turnOff: Boolean) {
        val name = simNameInput.text.toString().trim()
        if (name.isEmpty()) {
            appendLog("❌ Please enter a SIM name")
            return
        }

        val service = SimAccessibilityService.instance
        if (service == null) {
            appendLog("❌ Accessibility Service not running!")
            appendLog("   Go to Settings → Accessibility → SIM Scheduler → ON")
            return
        }

        appendLog("▶ Testing: '$name' → ${if (turnOff) "OFF" else "ON"}")
        appendLog("Watch the screen and Logcat...")
        SimAccessibilityService.diagnosticMode = false
        service.performSimToggle(name, turnOff)
    }

    private fun openSimSettings() {
        appendLog("Opening SIM Settings...")
        appendLog("Note the EXACT name shown for each SIM")
        appendLog("Then type it in the input field above")
        try {
            startActivity(Intent("android.settings.NETWORK_OPERATOR_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private fun appendLog(msg: String) {
        logs.append("$msg\n")
        logView.text = logs.toString()
    }
}
