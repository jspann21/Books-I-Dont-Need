package com.booktracker.booksidntneed.ui.dialog

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ThemeOverlay_BooksIDontNeed_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_edit_category, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val categoryNameEditText = view.findViewById<TextInputEditText>(R.id.editCategoryNameEditText)
        val editButton = view.findViewById<Button>(R.id.editCategoryButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelEditButton)
        val colorOption1 = view.findViewById<View>(R.id.colorOption1)
        val colorOption2 = view.findViewById<View>(R.id.colorOption2)
        val colorOption3 = view.findViewById<View>(R.id.colorOption3)
        val colorOption4 = view.findViewById<View>(R.id.colorOption4)
        val colorOption5 = view.findViewById<View>(R.id.colorOption5)
        // val colorOption6 = view.findViewById<View>(R.id.colorOption6) // Comment out or remove lines referencing colorOption6 and colorOption6Selected if they do not exist in the layout.
        val colorOption1Selected = view.findViewById<ImageView>(R.id.colorOption1Selected)
        val colorOption2Selected = view.findViewById<ImageView>(R.id.colorOption2Selected)
        val colorOption3Selected = view.findViewById<ImageView>(R.id.colorOption3Selected)
        val colorOption4Selected = view.findViewById<ImageView>(R.id.colorOption4Selected)
        val colorOption5Selected = view.findViewById<ImageView>(R.id.colorOption5Selected)
        // val colorOption6Selected = view.findViewById<ImageView>(R.id.colorOption6Selected) // Comment out or remove lines referencing colorOption6 and colorOption6Selected if they do not exist in the layout.

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
        colorOptions.forEach { (optionView, color, checkmark) ->
            optionView.setOnClickListener {
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
    }

    override fun onStart() {
        super.onStart()
        DialogStyling.apply(dialog)
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
