package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.booktracker.booksidntneed.ui.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ConfirmationDialogFragment : DialogFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val title = args.getString("title")
        val message = args.getString("message")
        val positive = args.getString("positive") ?: "OK"
        val negative = args.getString("negative")
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ ->
                // Use the new type-safe confirmation system
                viewModel.executeConfirmedAction()
                dismiss()
            }
        if (negative != null) {
            builder.setNegativeButton(negative) { _, _ ->
                // Cancel the confirmation action
                viewModel.cancelConfirmationAction()
                dismiss()
            }
        }
        return builder.create()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        viewModel.cancelConfirmationAction()
    }

    companion object {
        fun newInstance(title: String, message: String, positive: String = "OK", negative: String? = null): ConfirmationDialogFragment {
            val frag = ConfirmationDialogFragment()
            val args = Bundle()
            args.putString("title", title)
            args.putString("message", message)
            args.putString("positive", positive)
            if (negative != null) args.putString("negative", negative)
            frag.arguments = args
            return frag
        }
    }
} 
