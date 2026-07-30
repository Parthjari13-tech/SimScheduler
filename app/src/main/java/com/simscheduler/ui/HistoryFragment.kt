package com.simscheduler.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.simscheduler.databinding.FragmentHistoryBinding
import com.simscheduler.data.HistoryRepository

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadHistory()

        binding.clearHistoryBtn.setOnClickListener {
            HistoryRepository.clearHistory(requireContext())
            loadHistory()
        }
    }

    private fun loadHistory() {
        val items = HistoryRepository.getHistory(requireContext())
        if (items.isEmpty()) {
            binding.emptyLabel.visibility = View.VISIBLE
            binding.historyList.visibility = View.GONE
        } else {
            binding.emptyLabel.visibility = View.GONE
            binding.historyList.visibility = View.VISIBLE
            binding.historyList.text = items.joinToString("\n\n")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
