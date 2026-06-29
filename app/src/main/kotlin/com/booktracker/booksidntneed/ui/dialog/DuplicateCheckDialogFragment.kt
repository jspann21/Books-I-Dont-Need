package com.booktracker.booksidntneed.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.repository.BookRepository
import com.booktracker.booksidntneed.ui.MainViewModel

class DuplicateCheckDialogFragment : DialogFragment() {
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
        return inflater.inflate(R.layout.dialog_duplicate_check, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val existingTitleText = view.findViewById<TextView>(R.id.existingTitleText)
        val existingAuthorText = view.findViewById<TextView>(R.id.existingAuthorText)
        val existingIsbnText = view.findViewById<TextView>(R.id.existingIsbnText)
        val existingStoresText = view.findViewById<TextView>(R.id.existingStoresText)
        val newTitleText = view.findViewById<TextView>(R.id.newTitleText)
        val newAuthorText = view.findViewById<TextView>(R.id.newAuthorText)
        val newIsbnText = view.findViewById<TextView>(R.id.newIsbnText)
        val newStoreText = view.findViewById<TextView>(R.id.newStoreText)
        val newPriceText = view.findViewById<TextView>(R.id.newPriceText)
        val sameBookButton = view.findViewById<Button>(R.id.sameBookButton)
        val differentBookButton = view.findViewById<Button>(R.id.differentBookButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelDuplicateButton)

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
    }

    override fun onStart() {
        super.onStart()
        DialogStyling.apply(dialog)
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
