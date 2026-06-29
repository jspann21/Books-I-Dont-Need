package com.booktracker.booksidntneed.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import androidx.fragment.app.DialogFragment
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.Book
import androidx.fragment.app.activityViewModels
import com.booktracker.booksidntneed.ui.MainViewModel
import com.booktracker.booksidntneed.model.ManualEntryData

class ManualEntryDialogFragment : DialogFragment() {
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
        return inflater.inflate(R.layout.dialog_manual_book_entry, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleEditText = view.findViewById<TextInputEditText>(R.id.titleEditText)
        val authorEditText = view.findViewById<TextInputEditText>(R.id.authorEditText)
        val isbnEditText = view.findViewById<TextInputEditText>(R.id.isbnEditText)
        val priceEditText = view.findViewById<TextInputEditText>(R.id.priceEditText)
        val storeNameEditText = view.findViewById<TextInputEditText>(R.id.storeNameEditText)
        val storeUrlEditText = view.findViewById<TextInputEditText>(R.id.storeUrlEditText)
        val categoryDropdown = view.findViewById<MaterialAutoCompleteTextView>(R.id.categoryDropdown)
        val headerTitle = view.findViewById<TextView>(R.id.dialogHeaderTitle)
        val headerIcon = view.findViewById<ImageView>(R.id.dialogHeaderIcon)
        headerTitle.text = "Add Book Manually"
        headerIcon.setImageResource(R.drawable.ic_add)

        // Prefill fields if provided
        val args = requireArguments()
        titleEditText.setText(args.getString("title", ""))
        authorEditText.setText(args.getString("author", ""))
        isbnEditText.setText(args.getString("isbn", ""))
        priceEditText.setText(args.getString("price", ""))
        storeNameEditText.setText(args.getString("storeName", "Manual Entry"))
        storeUrlEditText.setText(args.getString("storeUrl", ""))
        categoryDropdown.setText(args.getString("category", "Uncategorized"), false)

        // Set up category dropdown (activity must provide categories)
        val categories = args.getStringArrayList("categories") ?: arrayListOf("Uncategorized")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        categoryDropdown.setAdapter(adapter)

        // Buttons
        view.findViewById<Button>(R.id.cancelButton).setOnClickListener { dismiss() }
        view.findViewById<Button>(R.id.saveButton).setOnClickListener {
            val title = titleEditText.text.toString().trim()
            val author = authorEditText.text.toString().trim()
            val isbn = isbnEditText.text.toString().trim().takeIf { it.isNotEmpty() }
            val price = priceEditText.text.toString().trim().toDoubleOrNull()
            val storeName = storeNameEditText.text.toString().trim().takeIf { it.isNotEmpty() } ?: "Manual Entry"
            val storeUrl = storeUrlEditText.text.toString().trim().takeIf { it.isNotEmpty() }
            val category = categoryDropdown.text.toString().trim().takeIf { it.isNotEmpty() } ?: "Uncategorized"
            if (title.isEmpty()) {
                Toast.makeText(requireContext(), "Title is required", Toast.LENGTH_SHORT).show()
                titleEditText.requestFocus()
                return@setOnClickListener
            }
            if (author.isEmpty()) {
                Toast.makeText(requireContext(), "Author is required", Toast.LENGTH_SHORT).show()
                authorEditText.requestFocus()
                return@setOnClickListener
            }
            if (isbn != null && !isValidISBN(isbn)) {
                Toast.makeText(requireContext(), "Please enter a valid ISBN (10 or 13 digits)", Toast.LENGTH_SHORT).show()
                isbnEditText.requestFocus()
                return@setOnClickListener
            }
            viewModel.confirmManualEntry(
                ManualEntryData(
                    title = title,
                    author = author,
                    isbn = isbn,
                    price = price,
                    storeName = storeName,
                    storeUrl = storeUrl,
                    category = category,
                    categories = categories
                )
            )
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        DialogStyling.apply(dialog)
    }

    private fun isValidISBN(isbn: String): Boolean {
        val cleanISBN = isbn.replace(Regex("[\\s-]"), "")
        return cleanISBN.matches(Regex("\\d{10}")) || cleanISBN.matches(Regex("\\d{9}[\\dX]")) || cleanISBN.matches(Regex("\\d{13}"))
    }
    companion object {
        fun newInstance(
            title: String = "",
            author: String = "",
            isbn: String = "",
            price: String = "",
            storeName: String = "Manual Entry",
            storeUrl: String = "",
            category: String = "Uncategorized",
            categories: ArrayList<String> = arrayListOf("Uncategorized")
        ): ManualEntryDialogFragment {
            val frag = ManualEntryDialogFragment()
            val args = Bundle()
            args.putString("title", title)
            args.putString("author", author)
            args.putString("isbn", isbn)
            args.putString("price", price)
            args.putString("storeName", storeName)
            args.putString("storeUrl", storeUrl)
            args.putString("category", category)
            args.putStringArrayList("categories", categories)
            frag.arguments = args
            return frag
        }
    }
} 
