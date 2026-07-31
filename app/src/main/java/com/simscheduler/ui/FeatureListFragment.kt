package com.simscheduler.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.simscheduler.R
import com.simscheduler.databinding.FragmentFeatureListBinding

class FeatureListFragment : Fragment() {

    private var _binding: FragmentFeatureListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeatureListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.simControlCard.setOnClickListener {
            openScreen(SimControlFragment(), "sim_control")
        }

        binding.audioControlCard.setOnClickListener {
            openScreen(AudioControlFragment(), "audio_control")
        }
    }

    private fun openScreen(fragment: Fragment, tag: String) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.homeContainer, fragment, tag)
            .addToBackStack(tag)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
