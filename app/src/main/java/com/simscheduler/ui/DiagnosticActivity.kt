package com.simscheduler.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.simscheduler.service.SimAccessibilityService
import com.simscheduler.util.SimDetector

class DiagnosticActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var simNameInput: EditText
    private val logs = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build UI programmatically
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "SIM Scheduler — Diagnostic"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        }

        // Detected SIMs info
        val simInfo = TextView(this).apply {
            val sims = SimDetector.detectSims(this@DiagnosticActivity)
            text = "Detected SIMs:\n" + sims.joinToString("\n") {
                "  Slot ${it.slot}: '${it.carrierName}' | ${it.phoneNumber}"
            }
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        // SIM name input for test
        val inputLabel = TextView(this).apply {
            text = "Enter SIM name to test toggle:"
            textSize = 14f
        }
        simNameInput = EditText(this).apply {
            hint = "e.g. Jio or LycaMobile"
            textSize = 16f
        }

        // Buttons
        val btnTestOff = Button(this).apply {
            text = "▶ TEST: Turn OFF this SIM"
            setOnClickListener { testToggle(turnOff = true) }
        }
        val btnTestOn = Button(this).apply {
            text = "▶ TEST: Turn ON this SIM"
            setOnClickListener { testToggle(turnOff = false) }
        }
        val btnDiagnostic = Button(this).apply {
            text = "📋 DUMP: Open SIM Settings & Log Screen"
            setOnClickListener { runDiagnostic() }
        }
        val btnOpenSim = Button(this).apply {
            text = "Open SIM Settings (manual check)"
            setOnClickListener {
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
        }

        // Log output
        val logLabel = TextView(this).apply {
            text = "\nLog output (check Logcat for tag 'SimSvc'):"
            textSize = 13f
        }
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400
            )
        }
        logView = TextView(this).apply {
            text = "Logs will appear in Android Studio Logcat\nFilter by tag: SimSvc"
            textSize = 12f
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xFF1E1E1E.toInt())
            setTextColor(0xFF00FF00.toInt())
        }
        scrollView.addView(logView)

        root.addView(title)
        root.addView(simInfo)
        root.addView(inputLabel)
        root.addView(simNameInput)
        root.addView(btnTestOff)
        root.addView(btnTestOn)
        root.addView(btnDiagnostic)
        root.addView(btnOpenSim)
        root.addView(logLabel)
        root.addView(scrollView)

        val mainScroll = ScrollView(this)
        mainScroll.addView(root)
        setContentView(mainScroll)
    }

    private fun testToggle(turnOff: Boolean) {
        val simName = simNameInput.text.toString().trim()
        if (simName.isEmpty()) {
            appendLog("❌ Enter a SIM name first")
            return
        }

        val service = SimAccessibilityService.instance
        if (service == null) {
            appendLog("❌ Accessibility Service not running!")
            return
        }

        SimAccessibilityService.diagnosticMode = false
        appendLog("▶ Testing: '$simName' → ${if (turnOff) "OFF" else "ON"}")
        appendLog("Watch Logcat for tag: SimSvc")
        service.performSimToggle(simName, turnOff)
    }

    private fun runDiagnostic() {
        val service = SimAccessibilityService.instance
        if (service == null) {
            appendLog("❌ Accessibility Service not running!")
            return
        }

        SimAccessibilityService.diagnosticMode = true
        appendLog("📋 Diagnostic mode ON")
        appendLog("Opening SIM Settings...")
        appendLog("Check Logcat tag 'SimSvc' for full screen dump")

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
