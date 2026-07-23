package com.simscheduler.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

    // Auto-detected SIM info
    private var detectedSim1: DetectedSim? = null
    private var detectedSim2: DetectedSim? = null

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

    // ── Auto-detect SIMs ──────────────────────────────────────────────────────
    private fun detectAndDisplaySims() {
        val detectedSims = SimDetector.detectSims(this)

        if (detectedSims.isEmpty()) {
            showNoSimsFound()
            return
        }

        // SIM 1 (slot 0)
        detectedSims.getOrNull(0)?.let { sim ->
            detectedSim1 = sim
            binding.sim1Name.text = "SIM 1 — ${sim.carrierName}"
            binding.sim1Number.text = sim.phoneNumber.ifEmpty { "Slot ${sim.slot + 1}" }
            binding.sim1ActualName.text = "✓ Will match: \"${sim.carrierName}\" in Settings"
            binding.sim1ActualName.visibility = View.VISIBLE
        }

        // SIM 2 (slot 1)
        detectedSims.getOrNull(1)?.let { sim ->
            detectedSim2 = sim
            binding.sim2Name.text = "SIM 2 — ${sim.carrierName}"
            binding.sim2Number.text = sim.phoneNumber.ifEmpty { "Slot ${sim.slot + 1}" }
            binding.sim2ActualName.text = "✓ Will match: \"${sim.carrierName}\" in Settings"
            binding.sim2ActualName.visibility = View.VISIBLE
            binding.sim2Card.visibility = View.VISIBLE
        }
    }

    private fun showNoSimsFound() {
        binding.sim1Name.text = "No SIM detected"
        binding.sim1ActualName.text = "⚠ Grant Phone permission for SIM detection"
        binding.sim1ActualName.visibility = View.VISIBLE
    }

    // ── Save schedules using auto-detected carrier names ──────────────────────
    private fun saveSchedules() {
        val schedules = mutableListOf<SimSchedule>()

        detectedSim1?.let { sim ->
            schedules.add(SimSchedule(
                simSlot = sim.slot,
                simName = sim.carrierName,   // ← "LycaMobile" auto-detected
                simNumber = sim.phoneNumber,
                offHour = binding.sim1OffTimePicker.hour,
                offMinute = binding.sim1OffTimePicker.minute,
                onHour = binding.sim1OnTimePicker.hour,
                onMinute = binding.sim1OnTimePicker.minute,
                isEnabled = binding.sim1ScheduleToggle.isChecked
            ))
        }

        detectedSim2?.let { sim ->
            schedules.add(SimSchedule(
                simSlot = sim.slot,
                simName = sim.carrierName,   // ← "Jio" auto-detected
                simNumber = sim.phoneNumber,
                offHour = binding.sim2OffTimePicker.hour,
                offMinute = binding.sim2OffTimePicker.minute,
                onHour = binding.sim2OnTimePicker.hour,
                onMinute = binding.sim2OnTimePicker.minute,
                isEnabled = binding.sim2ScheduleToggle.isChecked
            ))
        }

        if (schedules.isEmpty()) {
            Toast.makeText(this, "❌ No SIMs detected!", Toast.LENGTH_SHORT).show()
            return
        }

        ScheduleRepository.saveSchedules(this, schedules)
        AlarmScheduler.scheduleAll(this, schedules)

        val summary = schedules.filter { it.isEnabled }
            .joinToString("\n") { "• ${it.simName}: OFF ${it.offHour}:${"%02d".format(it.offMinute)} → ON ${it.onHour}:${"%02d".format(it.onMinute)}" }

        Toast.makeText(this, "✅ Saved!\n$summary", Toast.LENGTH_LONG).show()
        updateNextAction(schedules)
    }

    private fun loadSavedSchedules() {
        val saved = ScheduleRepository.loadSchedules(this)
        saved.forEach { schedule ->
            when (schedule.simSlot) {
                0 -> {
                    binding.sim1ScheduleToggle.isChecked = schedule.isEnabled
                    binding.sim1OffTimePicker.hour = schedule.offHour
                    binding.sim1OffTimePicker.minute = schedule.offMinute
                    binding.sim1OnTimePicker.hour = schedule.onHour
                    binding.sim1OnTimePicker.minute = schedule.onMinute
                    updateSim1Visibility(schedule.isEnabled)
                }
                1 -> {
                    binding.sim2ScheduleToggle.isChecked = schedule.isEnabled
                    binding.sim2OffTimePicker.hour = schedule.offHour
                    binding.sim2OffTimePicker.minute = schedule.offMinute
                    binding.sim2OnTimePicker.hour = schedule.onHour
                    binding.sim2OnTimePicker.minute = schedule.onMinute
                    updateSim2Visibility(schedule.isEnabled)
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.sim1ScheduleToggle.setOnCheckedChangeListener { _, on -> updateSim1Visibility(on) }
        binding.sim2ScheduleToggle.setOnCheckedChangeListener { _, on -> updateSim2Visibility(on) }
        binding.saveButton.setOnClickListener { saveSchedules() }
        binding.enableAccessibilityBtn.setOnClickListener { openAccessibilitySettings() }
        binding.grantAlarmPermissionBtn.setOnClickListener { requestExactAlarmPermission() }
        binding.refreshSimBtn.setOnClickListener {
            detectAndDisplaySims()
            Toast.makeText(this, "SIM info refreshed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSim1Visibility(v: Boolean) {
        binding.sim1ScheduleContent.visibility = if (v) View.VISIBLE else View.GONE
    }

    private fun updateSim2Visibility(v: Boolean) {
        binding.sim2ScheduleContent.visibility = if (v) View.VISIBLE else View.GONE
    }

    private fun updateNextAction(schedules: List<SimSchedule>) {
        val enabled = schedules.filter { it.isEnabled }
        if (enabled.isEmpty()) { binding.nextActionLabel.text = "No active schedules"; return }
        val now = Calendar.getInstance()
        val nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        data class Action(val label: String, val minsUntil: Int)
        val actions = mutableListOf<Action>()
        enabled.forEach { s ->
            val offM = s.offHour * 60 + s.offMinute
            val onM  = s.onHour  * 60 + s.onMinute
            actions.add(Action("${s.simName} OFF", if (offM > nowMins) offM - nowMins else 1440 - nowMins + offM))
            actions.add(Action("${s.simName} ON",  if (onM  > nowMins) onM  - nowMins else 1440 - nowMins + onM))
        }
        val next = actions.minByOrNull { it.minsUntil } ?: return
        val h = next.minsUntil / 60; val m = next.minsUntil % 60
        binding.nextActionLabel.text = "Next: ${next.label} in ${h}h ${m}m"
    }

    private fun updateAccessibilityStatus() {
        val enabled = SimAccessibilityService.instance != null ||
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("$packageName/${SimAccessibilityService::class.java.canonicalName}") == true
        if (enabled) {
            binding.accessibilityStatus.text = "✅ Accessibility Service: Active"
            binding.accessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
            binding.enableAccessibilityBtn.visibility = View.GONE
        } else {
            binding.accessibilityStatus.text = "⚠️ Accessibility Service: Disabled"
            binding.accessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.orange))
            binding.enableAccessibilityBtn.visibility = View.VISIBLE
        }
    }

    private fun updatePermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                binding.grantAlarmPermissionBtn.visibility = View.VISIBLE
                binding.alarmPermissionStatus.text = "⚠️ Exact alarm permission needed"
            } else {
                binding.grantAlarmPermissionBtn.visibility = View.GONE
                binding.alarmPermissionStatus.text = "✅ Alarm permission granted"
            }
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
