package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.Category
import com.google.android.material.textfield.TextInputEditText

class EditCategoryDialogFragment : DialogFragment() {
    interface Listener {
        fun onEditCategoryConfirmed(category: Category, newName: String, newColor: String)
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
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_category, null)
        val categoryNameEditText = dialogView.findViewById<TextInputEditText>(R.id.editCategoryNameEditText)
        val editButton = dialogView.findViewById<Button>(R.id.editCategoryButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelEditButton)
        val colorOption1 = dialogView.findViewById<View>(R.id.colorOption1)
        val colorOption2 = dialogView.findViewById<View>(R.id.colorOption2)
        val colorOption3 = dialogView.findViewById<View>(R.id.colorOption3)
        val colorOption4 = dialogView.findViewById<View>(R.id.colorOption4)
        val colorOption5 = dialogView.findViewById<View>(R.id.colorOption5)
        // val colorOption6 = dialogView.findViewById<View>(R.id.colorOption6) // Comment out or remove lines referencing colorOption6 and colorOption6Selected if they do not exist in the layout.
        val colorOption1Selected = dialogView.findViewById<ImageView>(R.id.colorOption1Selected)
        val colorOption2Selected = dialogView.findViewById<ImageView>(R.id.colorOption2Selected)
        val colorOption3Selected = dialogView.findViewById<ImageView>(R.id.colorOption3Selected)
        val colorOption4Selected = dialogView.findViewById<ImageView>(R.id.colorOption4Selected)
        val colorOption5Selected = dialogView.findViewById<ImageView>(R.id.colorOption5Selected)
        // val colorOption6Selected = dialogView.findViewById<ImageView>(R.id.colorOption6Selected) // Comment out or remove lines referencing colorOption6 and colorOption6Selected if they do not exist in the layout.

        val args = requireArguments()
        val category = BundleCompat.getParcelable(args, "category", Category::class.java)!!
        categoryNameEditText.setText(category.name)
        categoryNameEditText.selectAll()
        var selectedColor = category.color

        val colorOptions = listOf(
            Triple(colorOption1, "#F27128", colorOption1Selected),
            Triple(colorOption2, "#83AC46", colorOption2Selected),
            Triple(colorOption3, "#3B82F6", colorOption3Selected),
            Triple(colorOption4, "#EAB308", colorOption4Selected),
            Triple(colorOption5, "#A855F7", colorOption5Selected),
            // Triple(colorOption6, "#F43F5E", colorOption6Selected) // Comment out or remove lines referencing colorOption6 and colorOption6Selected if they do not exist in the layout.
        )
        colorOptions.forEach { (view, color, checkmark) ->
            view.setOnClickListener {
                colorOptions.forEach { (_, _, otherCheckmark) ->
                    otherCheckmark.visibility = View.GONE
                }
                checkmark.visibility = View.VISIBLE
                selectedColor = color
            }
            // Set initial selection
            checkmark.visibility = if (color == category.color) View.VISIBLE else View.GONE
        }

        editButton.setOnClickListener {
            val newCategoryName = categoryNameEditText.text.toString().trim()
            if (newCategoryName.isEmpty()) {
                Toast.makeText(requireContext(), "Category name cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            listener?.onEditCategoryConfirmed(category, newCategoryName, selectedColor ?: "")
            dismiss()
        }
        cancelButton.setOnClickListener { dismiss() }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    companion object {
        fun newInstance(category: Category): EditCategoryDialogFragment {
            val frag = EditCategoryDialogFragment()
            val args = Bundle()
            args.putParcelable("category", category)
            frag.arguments = args
            return frag
        }
    }
} 
