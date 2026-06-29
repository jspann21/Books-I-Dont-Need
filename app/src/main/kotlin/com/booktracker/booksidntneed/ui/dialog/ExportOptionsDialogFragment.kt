package com.booktracker.booksidntneed.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ThemeOverlay_BooksIDontNeed_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_export_complete, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.saveButton).setOnClickListener {
            listener?.onSaveToDevice()
            dismiss()
        }
        view.findViewById<Button>(R.id.shareButton).setOnClickListener {
            listener?.onShare()
            dismiss()
        }
        view.findViewById<Button>(R.id.cancelButton).setOnClickListener {
            listener?.onCancel()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        DialogStyling.apply(dialog)
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