package com.simscheduler.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.simscheduler.service.SimAccessibilityService

class DiagnosticActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var slotInput: EditText
    private val logs = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "🔧 SIM Diagnostic Tool"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        })

        root.addView(TextView(this).apply {
            text = "Test toggling SIM by slot number:\n" +
                   "  0 = SIM 1 (first physical slot)\n" +
                   "  1 = SIM 2 (second physical slot)"
            textSize = 14f
            setPadding(0, 0, 0, 24)
        })

        root.addView(TextView(this).apply {
            text = "Enter SIM slot number to test:"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        })

        slotInput = EditText(this).apply {
            hint = "0 = SIM 1,  1 = SIM 2"
            textSize = 18f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
            setPadding(16, 12, 16, 12)
        }
        root.addView(slotInput)

        root.addView(Button(this).apply {
            text = "▶ TEST: Turn OFF this SIM slot"
            setBackgroundColor(0xFFD32F2F.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { testToggle(turnOff = true) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120
            ).apply { setMargins(0, 16, 0, 8) }
        })

        root.addView(Button(this).apply {
            text = "▶ TEST: Turn ON this SIM slot"
            setBackgroundColor(0xFF388E3C.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { testToggle(turnOff = false) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120
            ).apply { setMargins(0, 0, 0, 8) }
        })

        root.addView(Button(this).apply {
            text = "📱 Open SIM Settings"
            setOnClickListener { openSimSettings() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 100
            ).apply { setMargins(0, 0, 0, 16) }
        })

        root.addView(TextView(this).apply {
            text = "LOG OUTPUT"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 8)
        })

        logView = TextView(this).apply {
            text = "Tap a test button to see results...\n" +
                   "Also check Logcat → filter tag: SimSvc"
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

        appendLog("Accessibility service: ${
            if (SimAccessibilityService.instance != null) "✅ Running"
            else "❌ Not running"
        }")
    }

    private fun testToggle(turnOff: Boolean) {
        val slot = slotInput.text.toString().trim().toIntOrNull()

        if (slot == null || slot !in 0..1) {
            appendLog("❌ Enter 0 (SIM 1) or 1 (SIM 2)")
            return
        }

        val service = SimAccessibilityService.instance
        if (service == null) {
            appendLog("❌ Accessibility Service not running!")
            return
        }

        appendLog("▶ slot=$slot → ${if (turnOff) "OFF" else "ON"}")
        appendLog("Watch the screen...")

        service.start(slot, turnOff)   // ← renamed from performSimToggle to start
    }

    private fun openSimSettings() {
        appendLog("Opening SIM Settings...")
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
