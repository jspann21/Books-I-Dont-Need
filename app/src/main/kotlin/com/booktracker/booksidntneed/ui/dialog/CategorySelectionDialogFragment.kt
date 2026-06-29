package com.booktracker.booksidntneed.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.Category
import com.booktracker.booksidntneed.ui.adapter.CategorySelectionAdapter
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.ViewModelProvider
import com.booktracker.booksidntneed.ui.MainViewModel

class CategorySelectionDialogFragment : DialogFragment() {
    
    private var onCategorySelected: ((Category) -> Unit)? = null
    private var onAllSelected: (() -> Unit)? = null
    private var onCategoryMenuClick: ((Category) -> Unit)? = null
    private var onValidateCategoryName: ((String) -> String?)? = null
    private var onAddCategory: ((String, String) -> Unit)? = null
    private var categories: List<Category> = emptyList()
    
    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_BOOK_TITLE = "book_title"
        private const val ARG_CURRENT_CATEGORY = "current_category"
        private const val ARG_SHOW_ALL_OPTION = "show_all_option"
        private const val ARG_SELECTED_CATEGORY = "selected_category"
        
        fun newInstance(
            title: String,
            bookTitle: String,
            currentCategoryName: String? = null,
            showAllOption: Boolean = false,
            selectedCategory: String? = null
        ): CategorySelectionDialogFragment {
            return CategorySelectionDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_BOOK_TITLE, bookTitle)
                    putString(ARG_CURRENT_CATEGORY, currentCategoryName)
                    putBoolean(ARG_SHOW_ALL_OPTION, showAllOption)
                    putString(ARG_SELECTED_CATEGORY, selectedCategory)
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ThemeOverlay_BooksIDontNeed_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_category_selection, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val title = arguments?.getString(ARG_TITLE) ?: "Select Category"
        val bookTitle = arguments?.getString(ARG_BOOK_TITLE) ?: ""
        val currentCategoryName = arguments?.getString(ARG_CURRENT_CATEGORY)
        val showAllOption = arguments?.getBoolean(ARG_SHOW_ALL_OPTION) ?: false
        val selectedCategory = arguments?.getString(ARG_SELECTED_CATEGORY)
        
        // Set up dialog title and book title
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.bookTitle).text = bookTitle
        
        setupCategoryList(view, currentCategoryName, showAllOption, selectedCategory)
        setupCreateCategorySection(view)
        setupCancelButton(view)

        // Observe categories from the activity's ViewModel
        val viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        viewModel.allCategories.observe(viewLifecycleOwner) { categories ->
            updateCategories(categories)
        }
    }
    
    override fun onStart() {
        super.onStart()
        DialogStyling.apply(dialog)
    }
    
    private fun setupCategoryList(
        view: View,
        currentCategoryName: String?,
        showAllOption: Boolean,
        selectedCategory: String?
    ) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.categoriesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        val categoryAdapter = CategorySelectionAdapter(
            onCategoryClick = { category ->
                onCategorySelected?.invoke(category)
                if (!showAllOption) {
                    dismiss()
                }
            },
            onCategoryMenuClick = { category ->
                onCategoryMenuClick?.invoke(category)
            },
            selectedCategoryName = currentCategoryName
        )
        
        recyclerView.adapter = categoryAdapter
        
        // Load categories
        var categoriesToShow = categories
        
        // Add "All" option for filtering
        if (showAllOption) {
            val allCategory = Category(
                name = getString(R.string.all_categories), 
                isDefault = true, 
                color = "#64748B"
            )
            categoriesToShow = listOf(allCategory) + categories
            
            // Update adapter click handling for "All" option
            if (onAllSelected != null) {
                val updatedAdapter = CategorySelectionAdapter(
                    onCategoryClick = { category ->
                        if (category.name == getString(R.string.all_categories)) {
                            onAllSelected?.invoke()
                        } else {
                            onCategorySelected?.invoke(category)
                        }
                        dismiss()
                    },
                    onCategoryMenuClick = { category ->
                        if (category.name != getString(R.string.all_categories)) {
                            onCategoryMenuClick?.invoke(category)
                        }
                    },
                    selectedCategoryName = selectedCategory
                )
                recyclerView.adapter = updatedAdapter
                updatedAdapter.submitList(categoriesToShow)
            } else {
                categoryAdapter.updateSelectedCategory(currentCategoryName)
                categoryAdapter.submitList(categoriesToShow)
            }
        } else {
            categoryAdapter.updateSelectedCategory(currentCategoryName)
            categoryAdapter.submitList(categoriesToShow)
        }
    }
    
    private fun setupCreateCategorySection(view: View) {
        val createCategoryCard = view.findViewById<MaterialCardView>(R.id.createCategoryCard)
        val addNewCategoryButton = view.findViewById<Button>(R.id.addNewCategoryButton)
        val cancelCreateButton = view.findViewById<Button>(R.id.cancelCreateButton)
        val createCategoryButton = view.findViewById<Button>(R.id.createCategoryButton)
        val newCategoryNameEditText = view.findViewById<TextInputEditText>(R.id.newCategoryNameEditText)
        
        // Color selection setup
        val colorOptions = listOf(
            Triple(view.findViewById(R.id.colorOption1), "#F27128", view.findViewById(R.id.colorOption1Selected)),
            Triple(view.findViewById(R.id.colorOption2), "#83AC46", view.findViewById(R.id.colorOption2Selected)),
            Triple(view.findViewById(R.id.colorOption3), "#269EBC", view.findViewById(R.id.colorOption3Selected)),
            Triple(view.findViewById(R.id.colorOption4), "#636FF6", view.findViewById(R.id.colorOption4Selected)),
            Triple(view.findViewById<View>(R.id.colorOption5), "#BD509D", view.findViewById<ImageView>(R.id.colorOption5Selected))
        )
        
        var selectedColor = colorOptions[0].second
        
        // Initially select the first color
        colorOptions[0].third.visibility = View.VISIBLE
        
        colorOptions.forEach { (view, color, checkmark) ->
            view.setOnClickListener {
                colorOptions.forEach { (_, _, otherCheckmark) ->
                    otherCheckmark.visibility = View.GONE
                }
                checkmark.visibility = View.VISIBLE
                selectedColor = color
            }
        }
        
        addNewCategoryButton.setOnClickListener {
            createCategoryCard.visibility = View.VISIBLE
            addNewCategoryButton.visibility = View.GONE
            newCategoryNameEditText.requestFocus()
            
            // Scroll to bottom
            view.findViewById<ScrollView>(R.id.dialogScrollView)?.let { scrollView ->
                scrollView.post {
                    scrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
        
        cancelCreateButton.setOnClickListener {
            createCategoryCard.visibility = View.GONE
            addNewCategoryButton.visibility = View.VISIBLE
            newCategoryNameEditText.text?.clear()
            
            // Reset color selection
            colorOptions.forEach { (_, _, checkmark) -> 
                checkmark.visibility = View.GONE
            }
            colorOptions[0].third.visibility = View.VISIBLE
            selectedColor = colorOptions[0].second
            
            // Scroll back to top
            view.findViewById<ScrollView>(R.id.dialogScrollView)?.let { scrollView ->
                scrollView.post {
                    scrollView.smoothScrollTo(0, 0)
                }
            }
        }
        
        createCategoryButton.setOnClickListener {
            val categoryName = newCategoryNameEditText.text.toString().trim()
            
            // Validate category name
            val validationError = onValidateCategoryName?.invoke(categoryName)
            if (validationError != null) {
                // Show error - you might want to add a Toast or error text view
                return@setOnClickListener
            }
            
            // Check if category already exists
            if (categories.any { it.name.equals(categoryName, ignoreCase = true) }) {
                // Show error - you might want to add a Toast or error text view
                return@setOnClickListener
            }
            
            // Create the category
            onAddCategory?.invoke(categoryName, selectedColor)
            
            // Hide create section and clear input
            createCategoryCard.visibility = View.GONE
            addNewCategoryButton.visibility = View.VISIBLE
            newCategoryNameEditText.text?.clear()
            
            // Reset color selection
            colorOptions.forEach { (_, _, checkmark) -> 
                checkmark.visibility = View.GONE
            }
            colorOptions[0].third.visibility = View.VISIBLE
            selectedColor = colorOptions[0].second
            
            // Scroll back to top
            view.findViewById<ScrollView>(R.id.dialogScrollView)?.let { scrollView ->
                scrollView.post {
                    scrollView.smoothScrollTo(0, 0)
                }
            }
        }
    }
    
    private fun setupCancelButton(view: View) {
        view.findViewById<Button>(R.id.cancelButton).setOnClickListener {
            dismiss()
        }
    }
    
    fun updateCategories(newCategories: List<Category>) {
        categories = newCategories.ifEmpty {
            // If the DB is empty, show the defaults (not persisted, but visible)
            Category.getDefaultCategories()
        }
        // Update the adapter if view is created
        view?.findViewById<RecyclerView>(R.id.categoriesRecyclerView)?.let { recyclerView ->
            val adapter = recyclerView.adapter
            if (adapter is CategorySelectionAdapter) {
                val currentCategoryName = arguments?.getString(ARG_CURRENT_CATEGORY)
                val showAllOption = arguments?.getBoolean(ARG_SHOW_ALL_OPTION) ?: false
                arguments?.getString(ARG_SELECTED_CATEGORY)
                
                var categoriesToShow = categories
                
                // Add "All" option for filtering
                if (showAllOption) {
                    val allCategory = Category(
                        name = getString(R.string.all_categories), 
                        isDefault = true, 
                        color = "#64748B"
                    )
                    categoriesToShow = listOf(allCategory) + categories
                }
                
                adapter.updateSelectedCategory(currentCategoryName)
                adapter.submitList(categoriesToShow)
            }
        }
    }
    
    fun setOnCategorySelectedListener(listener: (Category) -> Unit) {
        onCategorySelected = listener
    }
    
    fun setOnAllSelectedListener(listener: () -> Unit) {
        onAllSelected = listener
    }
    
    fun setOnCategoryMenuClickListener(listener: (Category) -> Unit) {
        onCategoryMenuClick = listener
    }
    
    fun setOnValidateCategoryNameListener(listener: (String) -> String?) {
        onValidateCategoryName = listener
    }
    
    fun setOnAddCategoryListener(listener: (String, String) -> Unit) {
        onAddCategory = listener
    }
} 
