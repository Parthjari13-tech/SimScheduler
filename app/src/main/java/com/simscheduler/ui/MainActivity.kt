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
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSimCards()
        loadSavedSchedules()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateAlarmPermissionStatus()
    }

    // ── Setup SIM cards — simple slot based, no name detection ───────────────
    private fun setupSimCards() {
        // SIM 1 — always slot 0
        binding.sim1Name.text   = "SIM 1"
        binding.sim1Slot.text   = "Slot 1 (First SIM card)"

        // SIM 2 — always slot 1
        binding.sim2Name.text   = "SIM 2"
        binding.sim2Slot.text   = "Slot 2 (Second SIM card)"
        binding.sim2Card.visibility = View.VISIBLE
    }

    private fun loadSavedSchedules() {
        ScheduleRepository.loadSchedules(this).forEach { s ->
            when (s.simSlot) {
                0 -> {
                    binding.sim1Toggle.isChecked      = s.isEnabled
                    binding.sim1OffPicker.hour        = s.offHour
                    binding.sim1OffPicker.minute      = s.offMinute
                    binding.sim1OnPicker.hour         = s.onHour
                    binding.sim1OnPicker.minute       = s.onMinute
                    binding.sim1ScheduleContent.visibility =
                        if (s.isEnabled) View.VISIBLE else View.GONE
                }
                1 -> {
                    binding.sim2Toggle.isChecked      = s.isEnabled
                    binding.sim2OffPicker.hour        = s.offHour
                    binding.sim2OffPicker.minute      = s.offMinute
                    binding.sim2OnPicker.hour         = s.onHour
                    binding.sim2OnPicker.minute       = s.onMinute
                    binding.sim2ScheduleContent.visibility =
                        if (s.isEnabled) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun setupButtons() {
        binding.sim1Toggle.setOnCheckedChangeListener { _, on ->
            binding.sim1ScheduleContent.visibility = if (on) View.VISIBLE else View.GONE
        }
        binding.sim2Toggle.setOnCheckedChangeListener { _, on ->
            binding.sim2ScheduleContent.visibility = if (on) View.VISIBLE else View.GONE
        }
        binding.saveButton.setOnClickListener { saveSchedules() }
        binding.enableAccessibilityBtn.setOnClickListener { openAccessibilitySettings() }
        binding.grantAlarmBtn.setOnClickListener { requestAlarmPermission() }
    }

    private fun saveSchedules() {
        val schedules = listOf(
            SimSchedule(
                simSlot   = 0,
                simLabel  = "SIM 1",
                offHour   = binding.sim1OffPicker.hour,
                offMinute = binding.sim1OffPicker.minute,
                onHour    = binding.sim1OnPicker.hour,
                onMinute  = binding.sim1OnPicker.minute,
                isEnabled = binding.sim1Toggle.isChecked
            ),
            SimSchedule(
                simSlot   = 1,
                simLabel  = "SIM 2",
                offHour   = binding.sim2OffPicker.hour,
                offMinute = binding.sim2OffPicker.minute,
                onHour    = binding.sim2OnPicker.hour,
                onMinute  = binding.sim2OnPicker.minute,
                isEnabled = binding.sim2Toggle.isChecked
            )
        )

        ScheduleRepository.saveSchedules(this, schedules)
        AlarmScheduler.scheduleAll(this, schedules)

        val active = schedules.filter { it.isEnabled }
        if (active.isEmpty()) {
            Toast.makeText(this, "No schedules enabled", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = active.joinToString("\n") {
            "SIM ${it.simSlot + 1}: OFF ${it.offHour}:${"%02d".format(it.offMinute)}" +
            " → ON ${it.onHour}:${"%02d".format(it.onMinute)}"
        }
        Toast.makeText(this, "✅ Saved!\n$msg", Toast.LENGTH_LONG).show()
        updateNextAction(schedules)
    }

    private fun updateNextAction(schedules: List<SimSchedule>) {
        val now = Calendar.getInstance()
        val nowM = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        data class A(val label: String, val mins: Int)
        val actions = mutableListOf<A>()
        schedules.filter { it.isEnabled }.forEach { s ->
            val offM = s.offHour * 60 + s.offMinute
            val onM  = s.onHour  * 60 + s.onMinute
            actions.add(A("SIM ${s.simSlot + 1} OFF",
                if (offM > nowM) offM - nowM else 1440 - nowM + offM))
            actions.add(A("SIM ${s.simSlot + 1} ON",
                if (onM  > nowM) onM  - nowM else 1440 - nowM + onM))
        }
        val next = actions.minByOrNull { it.mins } ?: return
        binding.nextActionLabel.text =
            "Next: ${next.label} in ${next.mins / 60}h ${next.mins % 60}m"
    }

    private fun updateAccessibilityStatus() {
        val on = SimAccessibilityService.instance != null ||
            Settings.Secure.getString(contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("$packageName/${SimAccessibilityService::class.java.canonicalName}") == true

        binding.accessibilityStatus.text =
            if (on) "✅ Accessibility Service: Active"
            else    "⚠️ Accessibility Service: Disabled"
        binding.accessibilityStatus.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.green else R.color.orange))
        binding.enableAccessibilityBtn.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun updateAlarmPermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .canScheduleExactAlarms()
            binding.alarmPermissionStatus.text =
                if (ok) "✅ Alarm permission granted"
                else    "⚠️ Exact alarm permission needed"
            binding.grantAlarmBtn.visibility = if (ok) View.GONE else View.VISIBLE
        } else {
            binding.alarmPermissionStatus.visibility = View.GONE
            binding.grantAlarmBtn.visibility = View.GONE
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Find 'SIM Scheduler' → turn ON", Toast.LENGTH_LONG).show()
    }

    private fun requestAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }
}
