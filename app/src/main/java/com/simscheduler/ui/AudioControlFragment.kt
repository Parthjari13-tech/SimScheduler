package com.simscheduler.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import com.simscheduler.databinding.FragmentAudioControlBinding

/**
 * Audio Control screen — UI only for now.
 * TODO: wire up real AudioManager logic (get/set stream volume, mute) later.
 */
class AudioControlFragment : Fragment() {

    private var _binding: FragmentAudioControlBinding? = null
    private val binding get() = _binding!!

    private var isMuted = false
    private var lastVolumeBeforeMute = 50

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAudioControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackNavigation()
        setupVolumeControl()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
