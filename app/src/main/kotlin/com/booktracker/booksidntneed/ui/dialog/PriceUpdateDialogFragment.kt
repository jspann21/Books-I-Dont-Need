package com.booktracker.booksidntneed.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.ui.MainViewModel
import com.booktracker.booksidntneed.ui.adapter.StoreProgressAdapter
import com.google.android.material.progressindicator.LinearProgressIndicator

class PriceUpdateDialogFragment : DialogFragment() {
    
    private var storeProgressAdapter: StoreProgressAdapter? = null
    
    // Add a reference to the shared ViewModel from the Activity
    private val viewModel: MainViewModel by activityViewModels()
    
    companion object {
        private const val ARG_PROGRESS_STATE = "progress_state"
        private const val ARG_BOOK_TITLE = "book_title"
        
        fun newInstance(progressState: MainViewModel.PriceUpdateProgressState, bookTitle: String): PriceUpdateDialogFragment {
            return PriceUpdateDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_PROGRESS_STATE, progressState)
                    putString(ARG_BOOK_TITLE, bookTitle)
                }
            }
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_price_update_progress, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize adapter
        storeProgressAdapter = StoreProgressAdapter()
        
        // Set up RecyclerView
        val recyclerView = view.findViewById<RecyclerView>(R.id.storeProgressRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = storeProgressAdapter
        
        // Set up button listeners
        view.findViewById<Button>(R.id.cancelButton).setOnClickListener {
            viewModel.clearPriceUpdateProgress()
            dismissAllowingStateLoss()
        }
        
        view.findViewById<Button>(R.id.doneButton).setOnClickListener {
            viewModel.clearPriceUpdateProgress()
            dismissAllowingStateLoss()
        }

        // Set initial title from arguments. It won't change during the update.
        val initialTitle = arguments?.getString(ARG_BOOK_TITLE) ?: "Book Price Update"
        view.findViewById<TextView>(R.id.bookTitle).text = initialTitle
        // The observer will handle all UI updates, including the initial one.
        setupObserver()
    }
    
    private fun setupObserver() {
        viewModel.priceUpdateProgress.observe(viewLifecycleOwner) { progressState ->
            if (progressState != null && progressState.isActive) {
                // We have a valid state, update the UI
                view?.let { updateContent(it, progressState) }
            } else {
                // The state is null or inactive, so the dialog should close itself.
                dismissAllowingStateLoss()
            }
        }
    }
    // --- END OF CHANGES ---
    
    private fun updateContent(dialogView: View, progressState: MainViewModel.PriceUpdateProgressState) {
        // The placeholder title is no longer needed here, as it's set in onViewCreated
        // Update progress bar with granular progress
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.overallProgressBar)
        val progressText = dialogView.findViewById<TextView>(R.id.progressText)
        
        if (progressState.totalTasks > 0) {
            // Use the new granular progress calculation
            progressBar.max = 100
            progressBar.progress = progressState.overallProgress
            progressText.text = "${progressState.completedTasks}/${progressState.totalTasks} tasks"
        } else if (progressState.totalStores > 0) {
            // Fallback to store-based progress
            val completedStores = progressState.stores.count { 
                it.status in listOf(
                    MainViewModel.StoreUpdateStatus.SUCCESS,
                    MainViewModel.StoreUpdateStatus.FAILED,
                    MainViewModel.StoreUpdateStatus.SKIPPED
                )
            }
            
            progressBar.max = progressState.totalStores
            progressBar.progress = completedStores
            progressText.text = "$completedStores/${progressState.totalStores}"
        }
        
        // Update current status
        val statusText = dialogView.findViewById<TextView>(R.id.currentStatusText)
        when {
            progressState.isComplete -> {
                val successCount = progressState.stores.count { it.status == MainViewModel.StoreUpdateStatus.SUCCESS }
                val failureCount = progressState.stores.count { it.status == MainViewModel.StoreUpdateStatus.FAILED }
                val skippedCount = progressState.stores.count { it.status == MainViewModel.StoreUpdateStatus.SKIPPED }
                
                statusText.text = when {
                    failureCount == 0 && skippedCount == 0 -> "All updates completed successfully!"
                    successCount > 0 -> "Updates completed: $successCount succeeded, $failureCount failed, $skippedCount skipped"
                    else -> "Updates failed for all stores"
                }
                
                // Show Done button and hide Cancel
                dialogView.findViewById<Button>(R.id.cancelButton).visibility = View.GONE
                dialogView.findViewById<Button>(R.id.doneButton).visibility = View.VISIBLE
            }
            progressState.currentStoreIndex < progressState.stores.size -> {
                val currentStore = progressState.stores[progressState.currentStoreIndex]
                statusText.text = "Updating ${currentStore.storeName}..."
            }
            else -> {
                statusText.text = "Starting price updates..."
            }
        }
        
        // Update store progress list
        storeProgressAdapter?.submitList(progressState.stores)
    }
} 
