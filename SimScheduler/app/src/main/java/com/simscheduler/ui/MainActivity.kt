package com.simscheduler.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
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
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val schedules = mutableListOf<SimSchedule>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSimCards()
        loadSavedSchedules()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updatePermissionStatus()
    }

    private fun loadSimCards() {
        // Try to read real SIM info
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subs = subscriptionManager.activeSubscriptionInfoList

                if (subs != null && subs.isNotEmpty()) {
                    // SIM 1
                    if (subs.size > 0) {
                        val sim1 = subs[0]
                        binding.sim1Name.text = sim1.displayName ?: "SIM 1"
                        binding.sim1Number.text = sim1.number?.ifEmpty { "Slot ${sim1.simSlotIndex + 1}" }
                    }
                    // SIM 2
                    if (subs.size > 1) {
                        val sim2 = subs[1]
                        binding.sim2Name.text = sim2.displayName ?: "SIM 2"
                        binding.sim2Number.text = sim2.number?.ifEmpty { "Slot ${sim2.simSlotIndex + 1}" }
                        binding.sim2Card.visibility = View.VISIBLE
                    }
                    return
                }
            }
        } catch (e: SecurityException) {
            // READ_PHONE_STATE not granted — use defaults
        }

        // Fallback defaults
        binding.sim1Name.text = "SIM 1"
        binding.sim1Number.text = "Slot 1"
        binding.sim2Name.text = "SIM 2"
        binding.sim2Number.text = "Slot 2"
        binding.sim2Card.visibility = View.VISIBLE
    }

    private fun loadSavedSchedules() {
        val saved = ScheduleRepository.loadSchedules(this)

        saved.forEach { schedule ->
            when (schedule.simSlot) {
                0 -> populateSim1UI(schedule)
                1 -> populateSim2UI(schedule)
            }
        }
        schedules.clear()
        schedules.addAll(saved)
    }

    private fun populateSim1UI(schedule: SimSchedule) {
        binding.sim1ScheduleToggle.isChecked = schedule.isEnabled
        binding.sim1OffTimePicker.hour = schedule.offHour
        binding.sim1OffTimePicker.minute = schedule.offMinute
        binding.sim1OnTimePicker.hour = schedule.onHour
        binding.sim1OnTimePicker.minute = schedule.onMinute
        updateSim1ScheduleVisibility(schedule.isEnabled)
    }

    private fun populateSim2UI(schedule: SimSchedule) {
        binding.sim2ScheduleToggle.isChecked = schedule.isEnabled
        binding.sim2OffTimePicker.hour = schedule.offHour
        binding.sim2OffTimePicker.minute = schedule.offMinute
        binding.sim2OnTimePicker.hour = schedule.onHour
        binding.sim2OnTimePicker.minute = schedule.onMinute
        updateSim2ScheduleVisibility(schedule.isEnabled)
    }

    private fun setupClickListeners() {
        // SIM 1 schedule toggle
        binding.sim1ScheduleToggle.setOnCheckedChangeListener { _, isChecked ->
            updateSim1ScheduleVisibility(isChecked)
        }

        // SIM 2 schedule toggle
        binding.sim2ScheduleToggle.setOnCheckedChangeListener { _, isChecked ->
            updateSim2ScheduleVisibility(isChecked)
        }

        // Save button
        binding.saveButton.setOnClickListener {
            saveSchedules()
        }

        // Enable Accessibility button
        binding.enableAccessibilityBtn.setOnClickListener {
            openAccessibilitySettings()
        }

        // Grant alarm permission button (Android 12+)
        binding.grantAlarmPermissionBtn.setOnClickListener {
            requestExactAlarmPermission()
        }
    }

    private fun updateSim1ScheduleVisibility(visible: Boolean) {
        binding.sim1ScheduleContent.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateSim2ScheduleVisibility(visible: Boolean) {
        binding.sim2ScheduleContent.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun saveSchedules() {
        val newSchedules = mutableListOf<SimSchedule>()

        // Build SIM 1 schedule
        val sim1Name = binding.sim1Name.text.toString()
        val sim1Number = binding.sim1Number.text.toString()
        val sim1Enabled = binding.sim1ScheduleToggle.isChecked
        newSchedules.add(
            SimSchedule(
                simSlot = 0,
                simName = sim1Name,
                simNumber = sim1Number,
                offHour = binding.sim1OffTimePicker.hour,
                offMinute = binding.sim1OffTimePicker.minute,
                onHour = binding.sim1OnTimePicker.hour,
                onMinute = binding.sim1OnTimePicker.minute,
                isEnabled = sim1Enabled
            )
        )

        // Build SIM 2 schedule
        val sim2Name = binding.sim2Name.text.toString()
        val sim2Number = binding.sim2Number.text.toString()
        val sim2Enabled = binding.sim2ScheduleToggle.isChecked
        newSchedules.add(
            SimSchedule(
                simSlot = 1,
                simName = sim2Name,
                simNumber = sim2Number,
                offHour = binding.sim2OffTimePicker.hour,
                offMinute = binding.sim2OffTimePicker.minute,
                onHour = binding.sim2OnTimePicker.hour,
                onMinute = binding.sim2OnTimePicker.minute,
                isEnabled = sim2Enabled
            )
        )

        // Save and schedule alarms
        ScheduleRepository.saveSchedules(this, newSchedules)
        AlarmScheduler.scheduleAll(this, newSchedules)

        schedules.clear()
        schedules.addAll(newSchedules)

        Toast.makeText(this, "✅ Schedules saved!", Toast.LENGTH_SHORT).show()
        updateNextActionLabel(newSchedules)
    }

    private fun updateNextActionLabel(schedules: List<SimSchedule>) {
        val enabled = schedules.filter { it.isEnabled }
        if (enabled.isEmpty()) {
            binding.nextActionLabel.text = "No active schedules"
            return
        }

        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        // Find the next upcoming action
        data class NextAction(val simName: String, val action: String, val minutesUntil: Int)
        val actions = mutableListOf<NextAction>()

        enabled.forEach { s ->
            val offMins = s.offHour * 60 + s.offMinute
            val onMins = s.onHour * 60 + s.onMinute
            val untilOff = if (offMins > nowMinutes) offMins - nowMinutes else 1440 - nowMinutes + offMins
            val untilOn = if (onMins > nowMinutes) onMins - nowMinutes else 1440 - nowMinutes + onMins
            actions.add(NextAction(s.simName, "OFF", untilOff))
            actions.add(NextAction(s.simName, "ON", untilOn))
        }

        val next = actions.minByOrNull { it.minutesUntil }
        if (next != null) {
            val h = next.minutesUntil / 60
            val m = next.minutesUntil % 60
            binding.nextActionLabel.text = "Next: ${next.simName} ${next.action} in ${h}h ${m}m"
        }
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        if (isEnabled) {
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
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        // First check if our instance is alive
        if (SimAccessibilityService.instance != null) return true

        // Also check via Settings
        val service = "$packageName/${SimAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this,
            "Find 'SIM Scheduler' and turn it ON",
            Toast.LENGTH_LONG).show()
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}
