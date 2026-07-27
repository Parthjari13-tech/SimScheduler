package com.simscheduler.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.simscheduler.R
import com.simscheduler.data.ScheduleRepository
import com.simscheduler.data.SimSchedule
import com.simscheduler.databinding.ActivityMainBinding
import com.simscheduler.service.SimAccessibilityService
import com.simscheduler.util.AlarmScheduler
import com.simscheduler.util.DetectedSim
import com.simscheduler.util.SimDetector
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var detectedSim1: DetectedSim? = null
    private var detectedSim2: DetectedSim? = null

    // Stores the final name used for matching — auto or manual override
    private var sim1MatchName: String = ""
    private var sim2MatchName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detectAndDisplaySims()
        loadSavedSchedules()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updatePermissionStatus()
    }

    // ── Auto detect SIMs ──────────────────────────────────────────────────────
    private fun detectAndDisplaySims() {
        val sims = SimDetector.detectSims(this)

        sims.getOrNull(0)?.let { sim ->
            detectedSim1 = sim
            sim1MatchName = sim.carrierName

            binding.sim1Name.text = "SIM 1 — ${sim.carrierName}"
            binding.sim1Number.text = sim.phoneNumber.ifEmpty { "Slot ${sim.slot + 1}" }

            // Show auto detected name
            binding.sim1DetectedName.text = "Auto detected: \"${sim.carrierName}\""

            // Pre-fill override field with detected name
            binding.sim1OverrideInput.setText(sim.carrierName)
            binding.sim1MatchLabel.text = "✓ Will match: \"${sim.carrierName}\""
        }

        sims.getOrNull(1)?.let { sim ->
            detectedSim2 = sim
            sim2MatchName = sim.carrierName

            binding.sim2Name.text = "SIM 2 — ${sim.carrierName}"
            binding.sim2Number.text = sim.phoneNumber.ifEmpty { "Slot ${sim.slot + 1}" }

            binding.sim2DetectedName.text = "Auto detected: \"${sim.carrierName}\""
            binding.sim2OverrideInput.setText(sim.carrierName)
            binding.sim2MatchLabel.text = "✓ Will match: \"${sim.carrierName}\""
            binding.sim2Card.visibility = View.VISIBLE
        }
    }

    private fun setupClickListeners() {
        // SIM schedule toggles
        binding.sim1ScheduleToggle.setOnCheckedChangeListener { _, on ->
            binding.sim1ScheduleContent.visibility = if (on) View.VISIBLE else View.GONE
        }
        binding.sim2ScheduleToggle.setOnCheckedChangeListener { _, on ->
            binding.sim2ScheduleContent.visibility = if (on) View.VISIBLE else View.GONE
        }

        // SIM 1 override name input — updates match label live
        binding.sim1OverrideInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.trim() ?: ""
                if (typed.isNotEmpty()) {
                    sim1MatchName = typed
                    binding.sim1MatchLabel.text = "✓ Will match: \"$typed\""
                    binding.sim1MatchLabel.setTextColor(
                        if (typed == detectedSim1?.carrierName)
                            ContextCompat.getColor(this@MainActivity, R.color.green)
                        else
                            ContextCompat.getColor(this@MainActivity, R.color.orange)
                    )
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // SIM 2 override name input
        binding.sim2OverrideInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.trim() ?: ""
                if (typed.isNotEmpty()) {
                    sim2MatchName = typed
                    binding.sim2MatchLabel.text = "✓ Will match: \"$typed\""
                    binding.sim2MatchLabel.setTextColor(
                        if (typed == detectedSim2?.carrierName)
                            ContextCompat.getColor(this@MainActivity, R.color.green)
                        else
                            ContextCompat.getColor(this@MainActivity, R.color.orange)
                    )
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Reset to auto detected name
        binding.sim1ResetBtn.setOnClickListener {
            val name = detectedSim1?.carrierName ?: return@setOnClickListener
            binding.sim1OverrideInput.setText(name)
            sim1MatchName = name
            Toast.makeText(this, "Reset to: \"$name\"", Toast.LENGTH_SHORT).show()
        }

        binding.sim2ResetBtn.setOnClickListener {
            val name = detectedSim2?.carrierName ?: return@setOnClickListener
            binding.sim2OverrideInput.setText(name)
            sim2MatchName = name
            Toast.makeText(this, "Reset to: \"$name\"", Toast.LENGTH_SHORT).show()
        }

        // Refresh SIM detection
        binding.diagnosticBtn.setOnClickListener {
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }

        binding.refreshSimBtn.setOnClickListener {
            detectAndDisplaySims()
            Toast.makeText(this, "SIM info refreshed", Toast.LENGTH_SHORT).show()
        }

        // How to find exact name hint
        binding.sim1HintBtn.setOnClickListener { showHintDialog("SIM 1") }
        binding.sim2HintBtn.setOnClickListener { showHintDialog("SIM 2") }

        binding.saveButton.setOnClickListener { saveSchedules() }
        binding.enableAccessibilityBtn.setOnClickListener { openAccessibilitySettings() }
        binding.grantAlarmPermissionBtn.setOnClickListener { requestExactAlarmPermission() }
    }

    private fun showHintDialog(simLabel: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("How to find exact name for $simLabel")
            .setMessage(
                "1. Open Phone Settings\n" +
                "2. Tap 'SIMs & mobile network'\n" +
                "3. Look at the name shown next to $simLabel\n" +
                "   Example: 'Jio' or 'LycaMobile'\n\n" +
                "4. Type that EXACT name in the override field\n\n" +
                "The name must match exactly what Settings shows — " +
                "including capital letters and spaces."
            )
            .setPositiveButton("Open SIM Settings") { _, _ ->
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
            .setNegativeButton("Close", null)
            .show()
    }

    private fun saveSchedules() {
        // Validate override names are not empty
        if (sim1MatchName.isEmpty()) {
            Toast.makeText(this, "⚠️ Enter SIM 1 name", Toast.LENGTH_SHORT).show()
            return
        }
        if (sim2MatchName.isEmpty() && binding.sim2Card.visibility == View.VISIBLE) {
            Toast.makeText(this, "⚠️ Enter SIM 2 name", Toast.LENGTH_SHORT).show()
            return
        }

        val schedules = mutableListOf<SimSchedule>()

        // SIM 1 — uses override name if set, otherwise auto detected
        detectedSim1?.let { sim ->
            schedules.add(SimSchedule(
                simSlot = sim.slot,
                simName = sim1MatchName,     // ← uses whatever user typed
                simNumber = sim.phoneNumber,
                offHour = binding.sim1OffTimePicker.hour,
                offMinute = binding.sim1OffTimePicker.minute,
                onHour = binding.sim1OnTimePicker.hour,
                onMinute = binding.sim1OnTimePicker.minute,
                isEnabled = binding.sim1ScheduleToggle.isChecked
            ))
        }

        // SIM 2
        detectedSim2?.let { sim ->
            schedules.add(SimSchedule(
                simSlot = sim.slot,
                simName = sim2MatchName,     // ← uses whatever user typed
                simNumber = sim.phoneNumber,
                offHour = binding.sim2OffTimePicker.hour,
                offMinute = binding.sim2OffTimePicker.minute,
                onHour = binding.sim2OnTimePicker.hour,
                onMinute = binding.sim2OnTimePicker.minute,
                isEnabled = binding.sim2ScheduleToggle.isChecked
            ))
        }

        ScheduleRepository.saveSchedules(this, schedules)
        AlarmScheduler.scheduleAll(this, schedules)

        val msg = schedules.filter { it.isEnabled }
            .joinToString("\n") {
                "• ${it.simName}: OFF ${it.offHour}:${"%02d".format(it.offMinute)}" +
                " → ON ${it.onHour}:${"%02d".format(it.onMinute)}"
            }

        Toast.makeText(this, "✅ Saved!\n$msg", Toast.LENGTH_LONG).show()
        updateNextAction(schedules)
    }

    private fun loadSavedSchedules() {
        val saved = ScheduleRepository.loadSchedules(this)
        saved.forEach { s ->
            when (s.simSlot) {
                0 -> {
                    // Restore override name if it was saved
                    if (s.simName != detectedSim1?.carrierName) {
                        binding.sim1OverrideInput.setText(s.simName)
                        sim1MatchName = s.simName
                    }
                    binding.sim1ScheduleToggle.isChecked = s.isEnabled
                    binding.sim1OffTimePicker.hour = s.offHour
                    binding.sim1OffTimePicker.minute = s.offMinute
                    binding.sim1OnTimePicker.hour = s.onHour
                    binding.sim1OnTimePicker.minute = s.onMinute
                    binding.sim1ScheduleContent.visibility = if (s.isEnabled) View.VISIBLE else View.GONE
                }
                1 -> {
                    if (s.simName != detectedSim2?.carrierName) {
                        binding.sim2OverrideInput.setText(s.simName)
                        sim2MatchName = s.simName
                    }
                    binding.sim2ScheduleToggle.isChecked = s.isEnabled
                    binding.sim2OffTimePicker.hour = s.offHour
                    binding.sim2OffTimePicker.minute = s.offMinute
                    binding.sim2OnTimePicker.hour = s.onHour
                    binding.sim2OnTimePicker.minute = s.onMinute
                    binding.sim2ScheduleContent.visibility = if (s.isEnabled) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun updateNextAction(schedules: List<SimSchedule>) {
        val enabled = schedules.filter { it.isEnabled }
        if (enabled.isEmpty()) { binding.nextActionLabel.text = "No active schedules"; return }
        val now = Calendar.getInstance()
        val nowM = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        data class A(val label: String, val mins: Int)
        val actions = mutableListOf<A>()
        enabled.forEach { s ->
            val offM = s.offHour * 60 + s.offMinute
            val onM  = s.onHour  * 60 + s.onMinute
            actions.add(A("${s.simName} OFF", if (offM > nowM) offM - nowM else 1440 - nowM + offM))
            actions.add(A("${s.simName} ON",  if (onM  > nowM) onM  - nowM else 1440 - nowM + onM))
        }
        val next = actions.minByOrNull { it.mins } ?: return
        binding.nextActionLabel.text = "Next: ${next.label} in ${next.mins/60}h ${next.mins%60}m"
    }

    private fun updateAccessibilityStatus() {
        val on = SimAccessibilityService.instance != null ||
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("$packageName/${SimAccessibilityService::class.java.canonicalName}") == true
        binding.accessibilityStatus.text = if (on) "✅ Accessibility Service: Active" else "⚠️ Accessibility Service: Disabled"
        binding.accessibilityStatus.setTextColor(ContextCompat.getColor(this, if (on) R.color.green else R.color.orange))
        binding.enableAccessibilityBtn.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun updatePermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            binding.alarmPermissionStatus.text = if (ok) "✅ Alarm permission granted" else "⚠️ Exact alarm permission needed"
            binding.grantAlarmPermissionBtn.visibility = if (ok) View.GONE else View.VISIBLE
        } else {
            binding.alarmPermissionStatus.visibility = View.GONE
            binding.grantAlarmPermissionBtn.visibility = View.GONE
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Find 'SIM Scheduler' → turn ON", Toast.LENGTH_LONG).show()
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }
}
