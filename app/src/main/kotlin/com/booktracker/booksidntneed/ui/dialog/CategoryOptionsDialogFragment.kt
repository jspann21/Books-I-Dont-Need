package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.Category
import com.booktracker.booksidntneed.ui.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CategoryOptionsDialogFragment : DialogFragment(), EditCategoryDialogFragment.Listener {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_category_options, null)
        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val editCategoryCard = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.editCategoryCard)
        val deleteCategoryCard = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.deleteCategoryCard)
        val cancelButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)

        val args = requireArguments()
        val category = BundleCompat.getParcelable(args, "category", Category::class.java)!!
        dialogTitle.text = category.name

        editCategoryCard.setOnClickListener {
            // Show the edit category dialog
            val editDialogFragment = EditCategoryDialogFragment.newInstance(category)
            editDialogFragment.show(parentFragmentManager, "edit_category_dialog")
            dismiss()
        }
        deleteCategoryCard.setOnClickListener {
            viewModel.requestCategoryDeletion(category)
            dismiss()
        }
        cancelButton.setOnClickListener {
            dismiss()
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.setOnShowListener {
            DialogStyling.apply(dialog)
        }
        return dialog
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
