package com.booktracker.booksidntneed.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.network.WebScrapingService
import com.booktracker.booksidntneed.ui.adapter.SellerAdapter
import com.booktracker.booksidntneed.network.AbeBooksParser
import androidx.fragment.app.activityViewModels
import com.booktracker.booksidntneed.ui.MainViewModel
import androidx.core.os.BundleCompat
import com.booktracker.booksidntneed.network.SellerOption

class SellerSelectionDialogFragment : DialogFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var multipleSellerOptions: WebScrapingService.ScrapingResult.MultipleSellerOptions? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
    }
    override fun onDetach() {
        super.onDetach()
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
        return inflater.inflate(R.layout.dialog_seller_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val recyclerView = view.findViewById<RecyclerView>(R.id.seller_recycler_view)
        val cancelButton = view.findViewById<Button>(R.id.cancel_button)

        val args = requireArguments()
        val bookTitle = args.getString("bookTitle") ?: "Select Seller"
        val options = BundleCompat.getParcelableArrayList(args, "options", SellerOption::class.java) ?: arrayListOf()
        multipleSellerOptions = BundleCompat.getParcelable(args, "multipleSellerOptions", WebScrapingService.ScrapingResult.MultipleSellerOptions::class.java)
        dialogTitle.text = "Select Seller for \"$bookTitle\""

        val adapter = SellerAdapter(options) { selectedSeller ->
            multipleSellerOptions?.let { optionsObj ->
                viewModel.onSellerSelected(optionsObj, selectedSeller)
            }
            dismiss()
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        cancelButton.setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        DialogStyling.apply(dialog)
    }

    companion object {
        fun newInstance(
            bookTitle: String,
            options: ArrayList<SellerOption>,
            multipleSellerOptions: WebScrapingService.ScrapingResult.MultipleSellerOptions
        ): SellerSelectionDialogFragment {
            val frag = SellerSelectionDialogFragment()
            val args = Bundle()
            args.putString("bookTitle", bookTitle)
            args.putParcelableArrayList("options", options)
            args.putParcelable("multipleSellerOptions", multipleSellerOptions)
            frag.arguments = args
            return frag
        }
    }
} 
