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
import com.booktracker.booksidntneed.model.BookWithStores
import com.booktracker.booksidntneed.model.EditBookData
import com.booktracker.booksidntneed.ui.MainViewModel
import com.google.android.material.card.MaterialCardView

class BookOptionsDialogFragment : DialogFragment() {
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
        return inflater.inflate(R.layout.dialog_book_options, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bookWithStores = BundleCompat.getParcelable(
            requireArguments(),
            "bookWithStores",
            BookWithStores::class.java
        )!!

        view.findViewById<TextView>(R.id.dialogTitle).text = "Book Options"
        view.findViewById<TextView>(R.id.bookTitle).text = bookWithStores.book.title

        view.findViewById<MaterialCardView>(R.id.editBookDetailsCard).setOnClickListener {
            val firstStore = bookWithStores.stores.firstOrNull()
            if (firstStore != null) {
                val editData = EditBookData(
                    book = bookWithStores.book,
                    store = firstStore,
                    title = bookWithStores.book.title,
                    author = bookWithStores.book.author,
                    isbn = bookWithStores.book.isbn13 ?: bookWithStores.book.isbn10,
                    price = firstStore.price,
                    storeName = firstStore.storeName,
                    storeUrl = firstStore.storeUrl,
                    category = bookWithStores.book.category
                )
                viewModel.requestEditBook(editData)
            }
            dismiss()
        }
        view.findViewById<MaterialCardView>(R.id.changeCategoryCard).setOnClickListener {
            val fm = parentFragmentManager
            dismiss()
            it.post {
                val dialogFragment = CategorySelectionDialogFragment.newInstance(
                    title = "Change Category",
                    bookTitle = bookWithStores.book.title,
                    currentCategoryName = bookWithStores.book.category,
                    showAllOption = false
                )
                dialogFragment.setOnCategorySelectedListener { category ->
                    viewModel.updateBookCategory(bookWithStores.book.id, category.name)
                }
                dialogFragment.show(fm, "category_selection_dialog")
            }
        }
        view.findViewById<MaterialCardView>(R.id.updatePricesCard).setOnClickListener {
            viewModel.requestPriceUpdate(bookWithStores)
            dismiss()
        }
        view.findViewById<MaterialCardView>(R.id.deleteBookCard).setOnClickListener {
            viewModel.requestBookDeletion(bookWithStores)
            dismiss()
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton).setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        DialogStyling.apply(dialog)
    }

    companion object {
        fun newInstance(bookWithStores: BookWithStores): BookOptionsDialogFragment {
            val frag = BookOptionsDialogFragment()
            val args = Bundle()
            args.putParcelable("bookWithStores", bookWithStores)
            frag.arguments = args
            return frag
        }
    }
}
