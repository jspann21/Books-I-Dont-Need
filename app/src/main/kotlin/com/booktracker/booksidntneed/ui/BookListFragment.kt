package com.booktracker.booksidntneed.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.booktracker.booksidntneed.databinding.FragmentBookListBinding
import com.booktracker.booksidntneed.ui.adapter.BooksAdapter
import com.booktracker.booksidntneed.ui.dialog.DialogManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BookListFragment : Fragment() {
    private var _binding: FragmentBookListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var booksAdapter: BooksAdapter
    private var pendingScrollTarget: MainViewModel.ScrollTarget? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBookListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialogManager = DialogManager(
            requireActivity().supportFragmentManager,
            viewModel,
            requireContext(),
            layoutInflater
        )
        setupRecyclerView()
        setupObservers()
    }

    private var dialogManager: DialogManager? = null

    private fun setupRecyclerView() {
        booksAdapter = BooksAdapter(
            onBookClick = { bookWithStores ->
                // Show a toast for now (detail view not implemented)
                Toast.makeText(requireContext(), "Book: ${bookWithStores.book.title}", Toast.LENGTH_SHORT).show()
            },
            onStoreClick = { store ->
                // Open store URL in browser if valid
                val url = store.storeUrl
                if (url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                    startActivity(intent)
                } else {
                    Toast.makeText(requireContext(), "No valid URL available for ${store.storeName}.", Toast.LENGTH_SHORT).show()
                }
            },
            onBookMenuClick = { bookWithStores ->
                dialogManager?.showBookOptionsDialogFragment(bookWithStores)
            },
            onCategoryClick = { bookWithStores ->
                dialogManager?.showCategorySelection(bookWithStores.book.id)
            }
        )
        binding.booksRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = booksAdapter
            
            // Add scroll listener for haptic feedback on boundaries
            val scrollListener = object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    // Provide subtle haptic feedback for scroll boundaries
                    if (!recyclerView.canScrollVertically(-1) && dy < 0) {
                        // Reached top
                        requireContext().provideHapticFeedback(HapticType.BOUNDARY)
                    } else if (!recyclerView.canScrollVertically(1) && dy > 0) {
                        // Reached bottom
                        requireContext().provideHapticFeedback(HapticType.BOUNDARY)
                    }
                }
            }
            addOnScrollListener(scrollListener)
            // Remove listener when view is detached to avoid leaking the Fragment through the listener
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    removeOnScrollListener(scrollListener)
                    v.removeOnAttachStateChangeListener(this)
                }
            })
        }
    }

    private fun setupObservers() {
        viewModel.filteredBooks.observe(viewLifecycleOwner) { books ->
            booksAdapter.submitList(books) {
                // Handle scroll if needed
                pendingScrollTarget?.let { scrollTarget ->
                    if (scrollTarget.shouldScroll) {
                        if (scrollTarget.bookId != null) {
                            val position = books.indexOfFirst { it.book.id == scrollTarget.bookId }
                            if (position != -1) {
                                binding.booksRecyclerView.smoothScrollToPosition(position)
                            }
                        } else {
                            binding.booksRecyclerView.smoothScrollToPosition(0)
                        }
                    }
                    pendingScrollTarget = null
                }
            }
            updateEmptyState(books.isEmpty())
        }
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.scrollTarget.collectLatest { scrollTarget ->
                    pendingScrollTarget = scrollTarget
                }
            }
        }
        viewModel.isMinimalCardMode.observe(viewLifecycleOwner) { isMinimal ->
            booksAdapter.setMinimalMode(isMinimal)
        }
        viewModel.allCategories.observe(viewLifecycleOwner) { categories ->
            booksAdapter.updateCategoryColors(categories)
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateLayout.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.booksRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        // Break adapter and dialog manager references to avoid holding the Fragment/View
        binding.booksRecyclerView.adapter = null
        dialogManager = null
        _binding = null
        super.onDestroyView()
    }
} 