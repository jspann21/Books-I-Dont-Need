package com.booktracker.booksidntneed.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.Category
import com.booktracker.booksidntneed.ui.MainViewModel

class CategoryOptionsDialogFragment : DialogFragment(), EditCategoryDialogFragment.Listener {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ThemeOverlay_BooksIDontNeed_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_category_options, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val dialogTitle = view.findViewById<TextView>(R.id.dialogTitle)
        val editCategoryCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.editCategoryCard)
        val deleteCategoryCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.deleteCategoryCard)
        val cancelButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)

        val args = requireArguments()
        val category = BundleCompat.getParcelable(args, "category", Category::class.java)!!
        dialogTitle.text = category.name

        editCategoryCard.setOnClickListener {
            // Show the edit category dialog
            val fm = parentFragmentManager
            dismiss()
            it.post {
                val editDialogFragment = EditCategoryDialogFragment.newInstance(category)
                editDialogFragment.show(fm, "edit_category_dialog")
            }
        }
        deleteCategoryCard.setOnClickListener {
            viewModel.requestCategoryDeletion(category)
            dismiss()
        }
        cancelButton.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        DialogStyling.apply(dialog)
    }

    override fun onEditCategoryConfirmed(category: Category, newName: String, newColor: String) {
        viewModel.updateCustomCategory(category.name, newName, newColor)
    }

    companion object {
        fun newInstance(category: Category): CategoryOptionsDialogFragment {
            val frag = CategoryOptionsDialogFragment()
            val args = Bundle()
            args.putParcelable("category", category)
            frag.arguments = args
            return frag
        }
    }
} 
