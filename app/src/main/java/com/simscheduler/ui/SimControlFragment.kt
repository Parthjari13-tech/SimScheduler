package com.simscheduler.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import com.simscheduler.databinding.FragmentSimControlBinding
import com.simscheduler.service.SimAccessibilityService
import com.simscheduler.util.SimDetector

class SimControlFragment : Fragment() {

    private var _binding: FragmentSimControlBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSimControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSimInfo()
        setupButtons()
        setupBackNavigation()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupBackNavigation() {
        binding.backBtn.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (parentFragmentManager.backStackEntryCount > 0) {
                parentFragmentManager.popBackStack()
            } else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun loadSimInfo() {
        val sims = SimDetector.detectSims(requireContext())

        // SIM 1
        sims.getOrNull(0)?.let { sim ->
            binding.sim1Name.text     = "SIM 1"
            binding.sim1Carrier.text  = sim.carrierName
            binding.sim1Number.text   = sim.phoneNumber.ifEmpty { "Slot 1" }
            binding.sim1Slot.text     = "Slot ${sim.slot + 1}"
        } ?: run {
            binding.sim1Name.text    = "SIM 1"
            binding.sim1Carrier.text = "Not detected"
            binding.sim1Number.text  = "Slot 1"
        }

        // SIM 2
        sims.getOrNull(1)?.let { sim ->
            binding.sim2Name.text     = "SIM 2"
            binding.sim2Carrier.text  = sim.carrierName
            binding.sim2Number.text   = sim.phoneNumber.ifEmpty { "Slot 2" }
            binding.sim2Slot.text     = "Slot ${sim.slot + 1}"
            binding.sim2Card.visibility = View.VISIBLE
        } ?: run {
            binding.sim2Card.visibility = View.GONE
        }
    }

    private fun setupButtons() {
        // Refresh button
        binding.refreshBtn.setOnClickListener {
            loadSimInfo()
            updateStatus()
            Toast.makeText(requireContext(), "Refreshed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val isActive = SimAccessibilityService.instance != null
        binding.serviceStatus.text = if (isActive)
            "✅ Accessibility Service: Active"
        else
            "⚠️ Accessibility Service: Disabled"

        binding.serviceStatus.setTextColor(
            if (isActive) 0xFF1DB954.toInt() else 0xFFF5A623.toInt()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
