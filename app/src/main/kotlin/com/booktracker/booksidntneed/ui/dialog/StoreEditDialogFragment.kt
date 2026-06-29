package com.booktracker.booksidntneed.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.Book
import com.booktracker.booksidntneed.model.BookStore
import com.booktracker.booksidntneed.model.BookWithStores
import com.booktracker.booksidntneed.ui.MainViewModel
import androidx.core.os.BundleCompat

class StoreEditDialogFragment : DialogFragment() {
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
        return inflater.inflate(R.layout.dialog_store_options, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val dialogTitle = view.findViewById<TextView>(R.id.dialogTitle)
        val dialogSubtitle = view.findViewById<TextView>(R.id.dialogSubtitle)
        val editButton = view.findViewById<Button>(R.id.editBookButton)
        val deleteButton = view.findViewById<Button>(R.id.deleteStoreButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)

        val args = requireArguments()
        val book = BundleCompat.getParcelable(args, "book", Book::class.java)!!
        val store = BundleCompat.getParcelable(args, "store", BookStore::class.java)!!
        val hasMultipleStores = args.getBoolean("hasMultipleStores")
        dialogTitle.text = store.storeName
        dialogSubtitle.text = book.title
        deleteButton.visibility = if (hasMultipleStores) View.VISIBLE else View.GONE

        editButton.setOnClickListener {
            // Create EditBookData for this store
            val editData = com.booktracker.booksidntneed.model.EditBookData(
                book = book,
                store = store,
                title = book.title,
                author = book.author,
                isbn = book.isbn13 ?: book.isbn10,
                price = store.price,
                storeName = store.storeName,
                storeUrl = store.storeUrl,
                category = book.category
            )
            viewModel.requestEditBook(editData)
            dismiss()
        }
        deleteButton.setOnClickListener {
            viewModel.requestStoreDeletion(store)
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
        fun newInstance(book: Book, store: BookStore, hasMultipleStores: Boolean): StoreEditDialogFragment {
            val frag = StoreEditDialogFragment()
            val args = Bundle()
            args.putParcelable("book", book)
            args.putParcelable("store", store)
            args.putBoolean("hasMultipleStores", hasMultipleStores)
            frag.arguments = args
            return frag
        }
    }
} 
