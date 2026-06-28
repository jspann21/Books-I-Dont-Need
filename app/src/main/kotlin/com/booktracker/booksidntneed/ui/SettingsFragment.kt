package com.booktracker.booksidntneed.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.booktracker.booksidntneed.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    interface SettingsActionListener {
        fun onExportData()
        fun onImportData()
        fun onClearData()
    }

    private var actionListener: SettingsActionListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        actionListener = context as? SettingsActionListener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.exportCard.setOnClickListener { actionListener?.onExportData() }
        binding.importCard.setOnClickListener { actionListener?.onImportData() }
        // TODO: Implement clear data UI and logic
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 