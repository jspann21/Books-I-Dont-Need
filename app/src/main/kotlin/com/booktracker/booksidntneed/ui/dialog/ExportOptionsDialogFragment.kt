package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.booktracker.booksidntneed.R

class ExportOptionsDialogFragment : DialogFragment() {
    interface ExportOptionsListener {
        fun onSaveToDevice()
        fun onShare()
        fun onCancel()
    }

    private var listener: ExportOptionsListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is ExportOptionsListener -> parentFragment as ExportOptionsListener
            context is ExportOptionsListener -> context
            activity is ExportOptionsListener -> activity as ExportOptionsListener
            else -> null
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_export_complete, null)

        val saveButton = view.findViewById<View>(R.id.saveButton) as Button
        val shareButton = view.findViewById<View>(R.id.shareButton) as Button
        val cancelButton = view.findViewById<View>(R.id.cancelButton) as Button

        saveButton.setOnClickListener {
            listener?.onSaveToDevice()
            dismiss()
        }
        shareButton.setOnClickListener {
            listener?.onShare()
            dismiss()
        }
        cancelButton.setOnClickListener {
            listener?.onCancel()
            dismiss()
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(true)
            .create()
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    companion object {
        fun newInstance(): ExportOptionsDialogFragment {
            return ExportOptionsDialogFragment()
        }
    }
} 