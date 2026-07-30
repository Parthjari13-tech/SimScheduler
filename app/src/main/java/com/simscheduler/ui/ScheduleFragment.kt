package com.simscheduler.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.simscheduler.databinding.FragmentScheduleBinding
import com.simscheduler.data.ScheduleRepository
import com.simscheduler.data.SimSchedule
import com.simscheduler.util.AlarmScheduler
import java.util.Calendar

class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSavedSchedules()
        setupButtons()
    }

    private fun loadSavedSchedules() {
        ScheduleRepository.loadSchedules(requireContext()).forEach { s ->
            when (s.simSlot) {
                0 -> {
                    binding.sim1Toggle.isChecked = s.isEnabled
                    binding.sim1OffPicker.hour   = s.offHour
                    binding.sim1OffPicker.minute = s.offMinute
                    binding.sim1OnPicker.hour    = s.onHour
                    binding.sim1OnPicker.minute  = s.onMinute
                    binding.sim1ScheduleContent.visibility =
                        if (s.isEnabled) View.VISIBLE else View.GONE
                }
                1 -> {
                    binding.sim2Toggle.isChecked = s.isEnabled
                    binding.sim2OffPicker.hour   = s.offHour
                    binding.sim2OffPicker.minute = s.offMinute
                    binding.sim2OnPicker.hour    = s.onHour
                    binding.sim2OnPicker.minute  = s.onMinute
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

        ScheduleRepository.saveSchedules(requireContext(), schedules)
        AlarmScheduler.scheduleAll(requireContext(), schedules)

        val active = schedules.filter { it.isEnabled }
        if (active.isEmpty()) {
            Toast.makeText(requireContext(), "No schedules enabled", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "✅ Schedules saved!", Toast.LENGTH_SHORT).show()
        updateNextAction(schedules)
    }

    private fun updateNextAction(schedules: List<SimSchedule>) {
        val now  = Calendar.getInstance()
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
            "⏰ Next: ${next.label} in ${next.mins / 60}h ${next.mins % 60}m"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
