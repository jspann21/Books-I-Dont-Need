package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.repository.BookRepository
import com.booktracker.booksidntneed.ui.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DuplicateCheckDialogFragment : DialogFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_duplicate_check, null)
        val existingTitleText = dialogView.findViewById<TextView>(R.id.existingTitleText)
        val existingAuthorText = dialogView.findViewById<TextView>(R.id.existingAuthorText)
        val existingIsbnText = dialogView.findViewById<TextView>(R.id.existingIsbnText)
        val existingStoresText = dialogView.findViewById<TextView>(R.id.existingStoresText)
        val newTitleText = dialogView.findViewById<TextView>(R.id.newTitleText)
        val newAuthorText = dialogView.findViewById<TextView>(R.id.newAuthorText)
        val newIsbnText = dialogView.findViewById<TextView>(R.id.newIsbnText)
        val newStoreText = dialogView.findViewById<TextView>(R.id.newStoreText)
        val newPriceText = dialogView.findViewById<TextView>(R.id.newPriceText)
        val sameBookButton = dialogView.findViewById<Button>(R.id.sameBookButton)
        val differentBookButton = dialogView.findViewById<Button>(R.id.differentBookButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelDuplicateButton)

        val args = requireArguments()
        val duplicateResult = BundleCompat.getSerializable(
            args,
            "duplicateResult",
            BookRepository.BookAddResult.TitleAuthorDuplicate::class.java
        )!!

        val existingBook = duplicateResult.existingBookWithStores.book
        val existingStores = duplicateResult.existingBookWithStores.stores
        existingTitleText.text = existingBook.title
        existingAuthorText.text = existingBook.author
        existingIsbnText.text = when {
            !existingBook.isbn13.isNullOrBlank() && !existingBook.isbn10.isNullOrBlank() -> "ISBN-13: ${existingBook.isbn13} • ISBN-10: ${existingBook.isbn10}"
            !existingBook.isbn13.isNullOrBlank() -> "ISBN-13: ${existingBook.isbn13}"
            !existingBook.isbn10.isNullOrBlank() -> "ISBN-10: ${existingBook.isbn10}"
            else -> "No ISBN"
        }
        existingStoresText.text = if (existingStores.isEmpty()) {
            "No stores"
        } else {
            existingStores.joinToString(", ") { store -> "${store.storeName}${if (store.price != null) " ($${String.format("%.2f", store.price)})" else ""}" }
        }
        newTitleText.text = duplicateResult.newTitle
        newAuthorText.text = duplicateResult.newAuthor
        newIsbnText.text = if (!duplicateResult.newIsbn.isNullOrBlank()) {
            val cleanISBN = duplicateResult.newIsbn.replace(Regex("[\\s-]"), "")
            when {
                cleanISBN.matches(Regex("\\d{13}")) -> "ISBN-13: ${duplicateResult.newIsbn}"
                cleanISBN.matches(Regex("\\d{10}")) || cleanISBN.matches(Regex("\\d{9}[\\dX]")) -> "ISBN-10: ${duplicateResult.newIsbn}"
                else -> "ISBN: ${duplicateResult.newIsbn}"
            }
        } else {
            "No ISBN"
        }
        newStoreText.text = duplicateResult.newStoreName
        newPriceText.text = if (duplicateResult.newPrice != null) {
            "$${String.format("%.2f", duplicateResult.newPrice)}"
        } else {
            "No price"
        }

        sameBookButton.setOnClickListener {
            viewModel.confirmSameBook(duplicateResult)
            dismiss()
        }
        differentBookButton.setOnClickListener {
            viewModel.confirmDifferentBook(duplicateResult)
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
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    companion object {
        fun newInstance(duplicateResult: BookRepository.BookAddResult.TitleAuthorDuplicate): DuplicateCheckDialogFragment {
            val frag = DuplicateCheckDialogFragment()
            val args = Bundle()
            args.putSerializable("duplicateResult", duplicateResult)
            frag.arguments = args
            return frag
        }
    }
} 
