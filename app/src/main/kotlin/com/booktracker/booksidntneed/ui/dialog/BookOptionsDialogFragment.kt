package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.BookWithStores
import com.booktracker.booksidntneed.model.EditBookData
import com.booktracker.booksidntneed.ui.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class BookOptionsDialogFragment : DialogFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_book_options, null)
        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val bookTitle = dialogView.findViewById<TextView>(R.id.bookTitle)
        val editBookDetailsCard = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.editBookDetailsCard)
        val changeCategoryCard = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.changeCategoryCard)
        val updatePricesCard = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.updatePricesCard)
        val deleteBookCard = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.deleteBookCard)
        val cancelButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)

        val args = requireArguments()
        val bookWithStores = BundleCompat.getParcelable(args, "bookWithStores", BookWithStores::class.java)!!
        dialogTitle.text = "Book Options"
        bookTitle.text = bookWithStores.book.title

        editBookDetailsCard.setOnClickListener {
            // For edit book details, we need to show the edit dialog for the first store
            val firstStore = bookWithStores.stores.firstOrNull()
            if (firstStore != null) {
                // Create EditBookData for the first store
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
        changeCategoryCard.setOnClickListener {
            // For change category, we'll show the category selection dialog directly
            val dialogFragment = CategorySelectionDialogFragment.newInstance(
                title = "Change Category",
                bookTitle = bookWithStores.book.title,
                currentCategoryName = bookWithStores.book.category,
                showAllOption = false
            )
            dialogFragment.setOnCategorySelectedListener { category ->
                viewModel.updateBookCategory(bookWithStores.book.id, category.name)
            }
            dialogFragment.show(parentFragmentManager, "category_selection_dialog")
            dismiss()
        }
        updatePricesCard.setOnClickListener {
            viewModel.requestPriceUpdate(bookWithStores)
            dismiss()
        }
        deleteBookCard.setOnClickListener {
            viewModel.requestBookDeletion(bookWithStores)
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
