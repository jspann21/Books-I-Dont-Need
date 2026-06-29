package com.booktracker.booksidntneed.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.booktracker.booksidntneed.R
import android.widget.RadioGroup
import com.google.android.material.button.MaterialButton

class SortDialogFragment : DialogFragment() {
    
    private var onSortOptionSelected: ((String) -> Unit)? = null
    
    companion object {
        private const val ARG_CURRENT_SORT = "current_sort"
        
        fun newInstance(currentSort: String): SortDialogFragment {
            return SortDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CURRENT_SORT, currentSort)
                }
            }
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
        return inflater.inflate(R.layout.dialog_sort, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentSort = arguments?.getString(ARG_CURRENT_SORT) ?: "title"

        val sortOptionsGroup = view.findViewById<RadioGroup>(R.id.sortOptionsGroup)
        val checkedId = when (currentSort) {
            "title" -> R.id.sortTitle
            "author" -> R.id.sortAuthor
            "date" -> R.id.sortDateAdded
            "price" -> R.id.sortPrice
            else -> R.id.sortTitle
        }
        sortOptionsGroup.check(checkedId)
        sortOptionsGroup.setOnCheckedChangeListener { _, id ->
            val sortOption = when (id) {
                R.id.sortTitle -> "title"
                R.id.sortAuthor -> "author"
                R.id.sortDateAdded -> "date"
                R.id.sortPrice -> "price"
                else -> "title"
            }
            onSortOptionSelected?.invoke(sortOption)
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        DialogStyling.apply(dialog)
    }
    
    fun setOnSortOptionSelectedListener(listener: (String) -> Unit) {
        onSortOptionSelected = listener
    }
} 
