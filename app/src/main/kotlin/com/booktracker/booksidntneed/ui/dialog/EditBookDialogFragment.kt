package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.Book
import com.booktracker.booksidntneed.model.BookStore
import com.booktracker.booksidntneed.model.EditBookData
import com.booktracker.booksidntneed.ui.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

class EditBookDialogFragment : DialogFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_manual_book_entry, null)
        val titleEditText = dialogView.findViewById<TextInputEditText>(R.id.titleEditText)
        val authorEditText = dialogView.findViewById<TextInputEditText>(R.id.authorEditText)
        val isbnEditText = dialogView.findViewById<TextInputEditText>(R.id.isbnEditText)
        val priceEditText = dialogView.findViewById<TextInputEditText>(R.id.priceEditText)
        val storeNameEditText = dialogView.findViewById<TextInputEditText>(R.id.storeNameEditText)
        val storeUrlEditText = dialogView.findViewById<TextInputEditText>(R.id.storeUrlEditText)
        val categoryDropdown = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.categoryDropdown)
        val headerTitle = dialogView.findViewById<TextView>(R.id.dialogHeaderTitle)
        val headerIcon = dialogView.findViewById<ImageView>(R.id.dialogHeaderIcon)
        headerTitle.text = "Edit Book Details"
        headerIcon.setImageResource(R.drawable.ic_edit)

        // Prefill fields if provided
        val args = requireArguments()
        val book = BundleCompat.getParcelable(args, "book", Book::class.java)!!
        val store = BundleCompat.getParcelable(args, "store", BookStore::class.java)!!
        titleEditText.setText(args.getString("title", book.title))
        authorEditText.setText(args.getString("author", book.author))
        isbnEditText.setText(args.getString("isbn", book.isbn13 ?: book.isbn10 ?: ""))
        priceEditText.setText(args.getString("price", store.price?.toString() ?: ""))
        storeNameEditText.setText(args.getString("storeName", store.storeName))
        storeUrlEditText.setText(args.getString("storeUrl", store.storeUrl))
        categoryDropdown.setText(args.getString("category", book.category), false)

        // Set up category dropdown (activity must provide categories)
        val categories = args.getStringArrayList("categories") ?: arrayListOf("Uncategorized")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        categoryDropdown.setAdapter(adapter)

        // Buttons
        dialogView.findViewById<Button>(R.id.cancelButton).setOnClickListener { dismiss() }
        dialogView.findViewById<Button>(R.id.saveButton).apply {
            text = "Update Book"
            setOnClickListener {
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
                viewModel.confirmEditBook(
                    EditBookData(
                        book = book,
                        store = store,
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
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.setOnShowListener {
            DialogStyling.apply(dialog)
        }
        return dialog
    }
    private fun isValidISBN(isbn: String): Boolean {
        val cleanISBN = isbn.replace(Regex("[\\s-]"), "")
        return cleanISBN.matches(Regex("\\d{10}")) || cleanISBN.matches(Regex("\\d{9}[\\dX]")) || cleanISBN.matches(Regex("\\d{13}"))
    }
    companion object {
        fun newInstance(
            book: Book,
            store: BookStore,
            title: String = book.title,
            author: String = book.author,
            isbn: String = book.isbn13 ?: book.isbn10 ?: "",
            price: String = store.price?.toString() ?: "",
            storeName: String = store.storeName,
            storeUrl: String = store.storeUrl,
            category: String = book.category,
            categories: ArrayList<String> = arrayListOf("Uncategorized")
        ): EditBookDialogFragment {
            val frag = EditBookDialogFragment()
            val args = Bundle()
            args.putParcelable("book", book)
            args.putParcelable("store", store)
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
