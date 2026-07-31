package com.simscheduler.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
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
        setupVolumeControl()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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
        // SIM 1 manual toggle buttons
        binding.sim1BtnOff.setOnClickListener { manualToggle(0, turnOff = true) }
        binding.sim1BtnOn.setOnClickListener  { manualToggle(0, turnOff = false) }

        // SIM 2 manual toggle buttons
        binding.sim2BtnOff.setOnClickListener { manualToggle(1, turnOff = true) }
        binding.sim2BtnOn.setOnClickListener  { manualToggle(1, turnOff = false) }

        // Refresh button
        binding.refreshBtn.setOnClickListener {
            loadSimInfo()
            updateStatus()
            Toast.makeText(requireContext(), "Refreshed", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Volume Control (UI only for now — actual volume logic to be added later) ---

    private var isMuted = false
    private var lastVolumeBeforeMute = 50

    private fun setupVolumeControl() {
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.volumePercentText.text = "$progress%"
                if (progress > 0 && isMuted) {
                    isMuted = false
                    binding.muteToggleBtn.text = "🔇 Mute"
                }
                // TODO: apply actual volume change here later
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.muteToggleBtn.setOnClickListener {
            if (!isMuted) {
                lastVolumeBeforeMute = binding.volumeSeekBar.progress
                binding.volumeSeekBar.progress = 0
                binding.volumePercentText.text = "0%"
                binding.muteToggleBtn.text = "🔊 Unmute"
                isMuted = true
                // TODO: apply actual mute here later
            } else {
                binding.volumeSeekBar.progress = lastVolumeBeforeMute
                binding.volumePercentText.text = "$lastVolumeBeforeMute%"
                binding.muteToggleBtn.text = "🔇 Mute"
                isMuted = false
                // TODO: apply actual unmute/restore-volume here later
            }
        }
    }

    private fun manualToggle(slot: Int, turnOff: Boolean) {
        val svc = SimAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(requireContext(),
                "⚠️ Accessibility Service not running\nGo to Settings tab to enable it",
                Toast.LENGTH_LONG).show()
            return
        }

        val simLabel = "SIM ${slot + 1}"
        val action   = if (turnOff) "OFF" else "ON"
        Toast.makeText(requireContext(),
            "Turning $simLabel $action...", Toast.LENGTH_SHORT).show()

        svc.start(slot, turnOff)
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
