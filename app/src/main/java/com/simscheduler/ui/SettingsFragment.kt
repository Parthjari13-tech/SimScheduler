package com.simscheduler.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.simscheduler.R
import com.simscheduler.databinding.FragmentSettingsBinding
import com.simscheduler.service.SimAccessibilityService

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        updatePermissions()
    }

    private fun setupButtons() {
        binding.enableAccessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(requireContext(),
                "Find 'SIM Scheduler' → turn ON", Toast.LENGTH_LONG).show()
        }

        binding.grantAlarmBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                })
            }
        }

        binding.diagnosticBtn.setOnClickListener {
            startActivity(Intent(requireContext(), DiagnosticActivity::class.java))
        }

        binding.batteryOptBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                })
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun updatePermissions() {
        // Accessibility
        val accessOn = SimAccessibilityService.instance != null ||
            Settings.Secure.getString(requireContext().contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("${requireContext().packageName}/${SimAccessibilityService::class.java.canonicalName}") == true

        binding.accessibilityStatus.text =
            if (accessOn) "✅ Accessibility Service: Active"
            else          "⚠️ Accessibility Service: Disabled"
        binding.accessibilityStatus.setTextColor(
            if (accessOn) 0xFF1DB954.toInt() else 0xFFF5A623.toInt())
        binding.enableAccessibilityBtn.visibility =
            if (accessOn) View.GONE else View.VISIBLE

        // Alarm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmOk = (requireContext()
                .getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .canScheduleExactAlarms()
            binding.alarmStatus.text =
                if (alarmOk) "✅ Alarm permission granted"
                else         "⚠️ Alarm permission needed"
            binding.grantAlarmBtn.visibility = if (alarmOk) View.GONE else View.VISIBLE
        } else {
            binding.alarmStatus.visibility   = View.GONE
            binding.grantAlarmBtn.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
