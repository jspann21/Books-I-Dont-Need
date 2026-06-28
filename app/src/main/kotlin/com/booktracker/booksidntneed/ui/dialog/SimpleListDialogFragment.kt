package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class SimpleListDialogFragment : DialogFragment() {
    interface SimpleListDialogListener {
        fun onItemSelected(requestKey: String, which: Int)
        fun onDialogCancelled(requestKey: String)
    }

    private var listener: SimpleListDialogListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is SimpleListDialogListener -> parentFragment as SimpleListDialogListener
            context is SimpleListDialogListener -> context
            activity is SimpleListDialogListener -> activity as SimpleListDialogListener
            else -> null
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val title = args.getString(ARG_TITLE) ?: ""
        val options = args.getStringArray(ARG_OPTIONS) ?: arrayOf()
        val requestKey = args.getString(ARG_REQUEST_KEY) ?: "default"

        return AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(options) { _, which ->
                listener?.onItemSelected(requestKey, which)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                listener?.onDialogCancelled(requestKey)
            }
            .create()
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_OPTIONS = "options"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(title: String, options: Array<String>, requestKey: String): SimpleListDialogFragment {
            val fragment = SimpleListDialogFragment()
            val args = Bundle()
            args.putString(ARG_TITLE, title)
            args.putStringArray(ARG_OPTIONS, options)
            args.putString(ARG_REQUEST_KEY, requestKey)
            fragment.arguments = args
            return fragment
        }
    }
} 