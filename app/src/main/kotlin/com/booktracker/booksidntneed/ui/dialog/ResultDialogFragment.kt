package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ResultDialogFragment : DialogFragment() {
    interface Listener {
        fun onResultOk(tag: String?)
    }
    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? Listener ?: activity as? Listener
    }
    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val message = args.getString("message") ?: ""
        val explicitTitle = args.getString("title")
        val title = explicitTitle ?: message.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() && message.contains('\n') }
        val body = if (title != null) {
            if (explicitTitle != null) message else message.lineSequence().drop(1).joinToString("\n").trimStart()
        } else {
            message
        }
        val dialogTag = tag
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setMessage(body)
            .setPositiveButton("OK") { _, _ ->
                listener?.onResultOk(dialogTag)
            }
        if (title != null) {
            builder.setTitle(title)
        }
        return builder.create()
    }

    companion object {
        fun newInstance(message: String, title: String? = null): ResultDialogFragment {
            val frag = ResultDialogFragment()
            val args = Bundle()
            args.putString("message", message)
            if (title != null) args.putString("title", title)
            frag.arguments = args
            return frag
        }
    }
} 
