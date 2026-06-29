package com.booktracker.booksidntneed.ui

// Material 3 Expressive imports for motion springs and physics
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.booktracker.booksidntneed.BookTrackerApplication
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.databinding.ActivityMainBinding
import com.booktracker.booksidntneed.repository.BookRepository
import com.booktracker.booksidntneed.ui.dialog.CategoryOptionsDialogFragment
import com.booktracker.booksidntneed.ui.dialog.CategorySelectionDialogFragment
import com.booktracker.booksidntneed.ui.dialog.ConfirmationDialogFragment
import com.booktracker.booksidntneed.ui.dialog.DuplicateCheckDialogFragment
import com.booktracker.booksidntneed.ui.dialog.EditBookDialogFragment
import com.booktracker.booksidntneed.ui.dialog.ExportOptionsDialogFragment
import com.booktracker.booksidntneed.ui.dialog.ManualEntryDialogFragment
import com.booktracker.booksidntneed.ui.dialog.PriceUpdateDialogFragment
import com.booktracker.booksidntneed.ui.dialog.ResultDialogFragment
import com.booktracker.booksidntneed.ui.dialog.SellerSelectionDialogFragment
import com.booktracker.booksidntneed.ui.dialog.SettingsDialogFragment
import com.booktracker.booksidntneed.ui.dialog.SimpleListDialogFragment
import com.booktracker.booksidntneed.ui.dialog.SortDialogFragment
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import com.booktracker.booksidntneed.utils.DataExportService
import com.booktracker.booksidntneed.work.AutoUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.util.Locale
import androidx.appcompat.R as AppCompatR

class MainActivity : AppCompatActivity(),
    ExportOptionsDialogFragment.ExportOptionsListener,
    SimpleListDialogFragment.SimpleListDialogListener,
    SettingsDialogFragment.SettingsDialogListener {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var dataExportService: DataExportService
    private var savingToLibraryDotsJob: Job? = null
    
    // Activity result launchers for file operations
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                handleExportResult(uri)
            }
        }
    }
    
    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleImportFile(it) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission is granted. Continue the action or workflow in your app.
        } else {
            // Explain to the user that the feature is unavailable because the
            // feature requires a permission that the user has denied. At the
            // same time, respect the user's decision. Don't link to system
            // settings in an effort to convince the user to change their
            // decision.
            Toast.makeText(
                this,
                "Notifications permission denied. Price update notifications will not be shown.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    // Material 3 Expressive motion springs and haptics
    private lateinit var vibrator: Vibrator
    private val springAnimations = mutableListOf<SpringAnimation>()
    
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as BookTrackerApplication).repository, application)
    }
    
    // Exported data is now stored in ViewModel to survive configuration changes
    
    // Remove currentBookToUpdatePrices variable
    
    // Helper to check if activity is valid (not finishing or destroyed)
    private fun isActivityValid(): Boolean {
        return !(isFinishing || isDestroyed)
    }
    // Add ActivityResultLauncher for ACTION_CREATE_DOCUMENT
    private val saveToDeviceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isActivityValid()) return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                val exportedData = viewModel.lastExportedData.value
                if (exportedData == null) {
                    Toast.makeText(this, "Export failed: No data to write.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(exportedData.csvData.toByteArray())
                    }
                    Toast.makeText(this, "Exported to device!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } ?: run {
                Toast.makeText(this, "Export failed: No URI returned.", Toast.LENGTH_LONG).show()
            }
            // Clean up temp file after successful save or error
            viewModel.clearLastExportedData()
        } else {
            // Clean up temp file if user cancels
            viewModel.clearLastExportedData()
        }
    }
    
    private var pendingScrollTarget: MainViewModel.ScrollTarget? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize services
        dataExportService = DataExportService(applicationContext)

        // Initialize Material 3 Expressive components
        initializeExpressiveMotion()

        // Replace book list area with BookListFragment
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(
                    R.id.booksContentFragmentContainer, // This will be a new FrameLayout in the layout
                    BookListFragment(),
                    "BookListFragment"
                )
            }
        }

        setupClickListeners()
        setupObservers()
        maybeShowRecentPriceChanges()
        registerUpdateReceiver()
        handleIncomingIntent(intent)
        requestNotificationPermission()
    }

    private var updateReceiver: BroadcastReceiver? = null

    private fun registerUpdateReceiver() {
        val filter = IntentFilter(AutoUpdateWorker.ACTION_SUMMARY_READY)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                lifecycleScope.launch {
                    val json = AutoUpdatePreferences.recentChangesJson(this@MainActivity).firstOrNull()
                    if (!json.isNullOrBlank()) {
                        val dialog = com.booktracker.booksidntneed.ui.dialog.RecentPriceChangesDialogFragment.newInstance(json)
                        if (!isFinishing && !isDestroyed) {
                            dialog.show(supportFragmentManager, "recent_price_changes_dialog")
                        }
                    }
                }
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        ContextCompat.registerReceiver(this, receiver, filter, flags)
        updateReceiver = receiver
    }

    private fun unregisterUpdateReceiver() {
        try { updateReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        updateReceiver = null
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // You can use the API that requires the permission.
                }
                shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) -> {
                    // In an educational UI, explain to the user why your app requires this
                    // permission for a specific feature to behave as expected, and what
                    // features are disabled if it's declined. In this UI, include a
                    // "cancel" or "no thanks" button that lets the user continue
                    // using your app without granting the permission.
                    // showInContextUI(...)
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    // You can directly ask for the permission.
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun maybeShowRecentPriceChanges() {
        lifecycleScope.launch {
            val json = AutoUpdatePreferences.recentChangesJson(this@MainActivity).firstOrNull()
            if (!json.isNullOrBlank()) {
                // Show a dialog listing recent price changes
                val dialog = com.booktracker.booksidntneed.ui.dialog.RecentPriceChangesDialogFragment.newInstance(json)
                dialog.show(supportFragmentManager, "recent_price_changes_dialog")
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }
    
    override fun onDestroy() {
        unregisterUpdateReceiver()
        super.onDestroy()
        
        // Clean up Material 3 Expressive spring animations
        cleanupSpringAnimations()
    }
    
    /**
     * Clean up all spring animations to prevent memory leaks
     */
    private fun cleanupSpringAnimations() {
        springAnimations.forEach { animation ->
            animation.cancel()
        }
        springAnimations.clear()
    }
    
    // Toolbar removed for Amazon-style layout
    
    private fun setupClickListeners() {
        setupSearchUi()
    }

    private fun setupSearchUi() {
        val initialQuery = viewModel.searchQuery.value.orEmpty()
        if (initialQuery.isNotBlank()) {
            binding.searchEditText.setText(initialQuery)
            setSearchModeVisible(true, requestFocus = false)
        }

        binding.searchIcon.setOnClickListener {
            provideHapticFeedback(HapticType.CONFIRMATION)
            it.animateClick()

            if (binding.searchEditText.isVisible) {
                if (binding.searchEditText.text.isNullOrBlank()) {
                    setSearchModeVisible(false)
                } else {
                    binding.searchEditText.text = null
                }
            } else {
                setSearchModeVisible(true)
            }
        }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.searchEditText.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(view)
                view.clearFocus()
                if (binding.searchEditText.text.isNullOrBlank()) {
                    setSearchModeVisible(false)
                }
                true
            } else {
                false
            }
        }
    }

    private fun setSearchModeVisible(isVisible: Boolean, requestFocus: Boolean = true) {
        binding.searchEditText.visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.sortButton.visibility = if (isVisible) View.GONE else View.VISIBLE
        binding.filterButton.visibility = if (isVisible) View.GONE else View.VISIBLE
        binding.cardViewToggleButton.visibility = if (isVisible) View.GONE else View.VISIBLE
        binding.settingsButton.visibility = if (isVisible) View.GONE else View.VISIBLE

        if (isVisible && requestFocus) {
            binding.searchEditText.requestFocus()
            binding.searchEditText.post { showKeyboard(binding.searchEditText) }
        } else if (!isVisible) {
            binding.searchEditText.text = null
            binding.searchEditText.clearFocus()
            hideKeyboard(binding.searchEditText)
        }
    }

    private fun showKeyboard(view: View) {
        WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
    }

    private fun hideKeyboard(view: View) {
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }
    
    private fun setupObservers() {
        // Observe modern loading state
        viewModel.loadingState.observe(this) { loadingState ->
            updateAddButtonState(loadingState)
        }
        
        // Observe detailed status
        viewModel.detailedStatus.observe(this) { detailedStatus ->
            updateDetailedStatus(detailedStatus)
        }
        
        // Restore filteredBooks observer
        viewModel.filteredBooks.observe(this) {
            pendingScrollTarget?.let { scrollTarget ->
                if (scrollTarget.shouldScroll && scrollTarget.bookId == null) {
                    provideHapticFeedback(HapticType.CONFIRMATION)
                }
                pendingScrollTarget = null
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scrollTarget.collectLatest { scrollTarget ->
                    pendingScrollTarget = scrollTarget
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiMessage.collectLatest { message ->
                    val text = if (message.formatArgs.isNotEmpty()) {
                        getString(message.resId, *message.formatArgs.toTypedArray())
                    } else {
                        getString(message.resId)
                    }
                    if (message.type == MessageType.SUCCESS) {
                        provideHapticFeedback(HapticType.SUCCESS)
                        Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show()
                    } else {
                        provideHapticFeedback(HapticType.ERROR)
                        Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.viewEvent.collectLatest { event ->
                    when (event) {
                        is ViewEvent.ShowDuplicateCheck -> {
                            val dialogFragment = DuplicateCheckDialogFragment.newInstance(event.duplicateResult)
                            dialogFragment.show(supportFragmentManager, "duplicate_check_dialog")
                        }
                        is ViewEvent.ShowSellerSelection -> {
                            val options = ArrayList(event.options.sellerOptions.sortedBy { it.price })
                            val dialogFragment = SellerSelectionDialogFragment.newInstance(event.options.bookTitle, options, event.options)
                            dialogFragment.show(supportFragmentManager, "seller_selection_dialog")
                        }
                        is ViewEvent.ShowManualEntry -> {
                            val dialogFragment = ManualEntryDialogFragment.newInstance(
                                title = event.data.title,
                                author = event.data.author,
                                isbn = event.data.isbn ?: "",
                                price = event.data.price?.toString() ?: "",
                                storeName = event.data.storeName ?: getString(R.string.manual_entry),
                                storeUrl = event.data.storeUrl ?: "",
                                category = event.data.category ?: getString(R.string.uncategorized),
                                categories = ArrayList(event.data.categories)
                            )
                            dialogFragment.show(supportFragmentManager, "manual_entry_dialog")
                        }
                        is ViewEvent.ShowEditBook -> {
                            val dialogFragment = EditBookDialogFragment.newInstance(
                                book = event.data.book,
                                store = event.data.store,
                                title = event.data.title,
                                author = event.data.author,
                                isbn = event.data.isbn ?: "",
                                price = event.data.price?.toString() ?: "",
                                storeName = event.data.storeName ?: event.data.store.storeName,
                                storeUrl = event.data.storeUrl ?: event.data.store.storeUrl,
                                category = event.data.category ?: event.data.book.category,
                                categories = ArrayList(event.data.categories)
                            )
                            dialogFragment.show(supportFragmentManager, "edit_book_dialog")
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.confirmationDialog.collectLatest { dialogState ->
                    val dialogTag = "confirmation_dialog"
                    val existingDialog = supportFragmentManager.findFragmentByTag(dialogTag) as? ConfirmationDialogFragment
                    existingDialog?.dismissAllowingStateLoss()
                    val dialog = ConfirmationDialogFragment.newInstance(
                        dialogState.title,
                        dialogState.message,
                        dialogState.positiveButton,
                        dialogState.negativeButton
                    )
                    dialog.show(supportFragmentManager, dialogTag)
                }
            }
        }
        // --- END REFACTOR ---

        // Observe price update progress
        viewModel.priceUpdateProgress.observe(this) { progressState ->
            val dialogTag = "price_update_dialog"
            val existingDialog = supportFragmentManager.findFragmentByTag(dialogTag) as? PriceUpdateDialogFragment

            if (progressState != null && progressState.isActive) {
                // Only show the dialog if it's not already on screen.
                // The dialog itself will now be responsible for handling subsequent updates.
                if (existingDialog == null) {
                    val bookTitle = progressState.bookTitle.ifEmpty { "Updating Prices..." }
                    val newDialog = PriceUpdateDialogFragment.newInstance(progressState, bookTitle)

                    // Make it non-cancelable to prevent accidental dismissal while updating.
                    newDialog.isCancelable = false 
                    newDialog.show(supportFragmentManager, dialogTag)
                }
                // If the dialog already exists, we do nothing. It will update itself.

            } else {
                // Progress is inactive (completed or cancelled), or state is null.
                // The dialog will dismiss itself when it observes this null state,
                // but this acts as a good safeguard.
                existingDialog?.dismissAllowingStateLoss()
            }
        }
        
        // Observe selected category to update filter button appearance
        viewModel.selectedCategory.observe(this) { selectedCategory ->
            updateFilterButtonAppearance(selectedCategory)
        }
    }
    
    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        // Extract URL from shared text (handles case where title + URL are shared together)
                        val extractedUrl = extractUrlFromText(sharedText)
                        if (extractedUrl.isNotEmpty()) {
                            Log.d("BookTracker", "Extracted URL from shared text: '$sharedText' -> '$extractedUrl'")
                            binding.inputBarView.urlEditText.setText(extractedUrl)
                            // Optionally auto-add the book
                            addBookFromUrl()
                        } else {
                            Log.w("BookTracker", "No valid URL found in shared text: '$sharedText'")
                            binding.inputBarView.urlEditText.setText(sharedText)
                            Toast.makeText(this, getString(R.string.no_valid_url_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val url = intent.dataString
                if (!url.isNullOrBlank()) {
                    binding.inputBarView.urlEditText.setText(url)
                }
            }
        }
    }
    
    // --- URL Extraction Helpers ---
    private fun extractAmazonShortUrl(text: String): String? = Regex("https?://a\\.co/d/\\w+", RegexOption.IGNORE_CASE).find(text)?.value
    private fun extractAmazonUrl(text: String): String? = Regex("https?://(?:www\\.)?amazon\\.[a-z]{2,3}(?:\\.[a-z]{2})?/\\S+", RegexOption.IGNORE_CASE).find(text)?.value
    private fun extractBarnesNobleUrl(text: String): String? = Regex("https?://(?:www\\.)?(?:barnesandnoble|bn)\\.com/\\S+", RegexOption.IGNORE_CASE).find(text)?.value
    private fun extractGoogleBooksUrl(text: String): String? = Regex("https?://(?:books\\.google\\.[a-z]{2,3}(?:\\.[a-z]{2})?|play\\.google\\.com/books)/\\S+", RegexOption.IGNORE_CASE).find(text)?.value
    private fun extractChristianBookUrl(text: String): String? = Regex("https?://(?:www\\.)?christianbook\\.com/\\S+", RegexOption.IGNORE_CASE).find(text)?.value
    private fun extractGenericUrl(text: String): String? = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(text)?.value
    private fun extractWithLinkify(text: String): String? {
        return try {
            val spannable = android.text.SpannableString(text)
            if (android.text.util.Linkify.addLinks(spannable, android.text.util.Linkify.WEB_URLS)) {
                val urls = spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)
                if (urls.isNotEmpty()) {
                    Log.d("BookTracker", "Linkify fallback found URL: '${urls[0].url}'")
                    return urls[0].url
                }
            }
            null
        } catch (e: Exception) {
            Log.e("BookTracker", "extractWithLinkify: Linkify fallback failed", e)
            null
        }
    }

    /**
     * Extracts URLs from text that may contain both title and URL
     * Handles cases like: "Book Title https://example.com/book"
     *
     * Supported URL patterns:
     * - Amazon: https://amazon.com/..., https://a.co/d/...
     * - Barnes & Noble: https://barnesandnoble.com/...
     * - Google Books: https://books.google.com/...
     * - ChristianBook: https://christianbook.com/...
     * - Generic: any https:// or http:// URL
     *
     * Now robustified: All regex parsing is wrapped in try-catch, and if all fail, Linkify is used as a fallback.
     */
    private fun extractUrlFromText(text: String): String {
        return try {
            Log.d("BookTracker", "Extracting URL from text: '$text'")
            extractAmazonShortUrl(text)
                ?: extractAmazonUrl(text)
                ?: extractBarnesNobleUrl(text)
                ?: extractGoogleBooksUrl(text)
                ?: extractChristianBookUrl(text)
                ?: extractGenericUrl(text)
                ?: extractWithLinkify(text)
                ?: ""
        } catch (e: Exception) {
            Log.e("BookTracker", "extractUrlFromText: Regex parsing failed", e)
            extractWithLinkify(text) ?: ""
        }
    }
    
    private fun addBookFromUrl() {
        val url = binding.inputBarView.urlEditText.text.toString().trim()
        if (url.isBlank()) {
            // Show manual entry dialog when no URL is provided
            showManualEntryDialogFragment()
            return
        }
        Log.d("BookTracker", "Adding book from URL: $url")
        
        // For now, use default category. In a full implementation, 
        // you might show a category selection dialog
        viewModel.addBookFromUrl(url, getString(R.string.uncategorized))
    }
    
    private fun updateAddButtonState(loadingState: MainViewModel.LoadingState) {
        when (loadingState) {
            MainViewModel.LoadingState.IDLE -> {
                // Instead of .background, use .backgroundTintList
                binding.inputBarView.addBookButton.backgroundTintList = getTint(R.color.colorPrimary)
                binding.inputBarView.addBookButton.setImageResource(R.drawable.ic_add)
                binding.inputBarView.addBookButton.isEnabled = true
                stopSavingToLibraryDotsAnimation()
                animateStatusTextChange(binding.inputBarView.addBookStatusText, "", false)
            }
            MainViewModel.LoadingState.ANALYZING_URL -> {
                binding.inputBarView.addBookButton.backgroundTintList = getTint(R.color.colorLoading)
                binding.inputBarView.addBookButton.setImageResource(R.drawable.ic_add)
                binding.inputBarView.addBookButton.isEnabled = false
                provideHapticFeedback(HapticType.SOFT_PRESS)
            }
            MainViewModel.LoadingState.CONNECTING_TO_SITE -> {
                binding.inputBarView.addBookButton.backgroundTintList = getTint(R.color.colorLoading)
                binding.inputBarView.addBookButton.setImageResource(R.drawable.ic_add)
                binding.inputBarView.addBookButton.isEnabled = false
            }
            MainViewModel.LoadingState.DOWNLOADING_PAGE -> {
                binding.inputBarView.addBookButton.backgroundTintList = getTint(R.color.colorLoading)
                binding.inputBarView.addBookButton.setImageResource(R.drawable.ic_add)
                binding.inputBarView.addBookButton.isEnabled = false
            }
            MainViewModel.LoadingState.PARSING_CONTENT -> {
                binding.inputBarView.addBookButton.backgroundTintList = getTint(R.color.colorLoading)
                binding.inputBarView.addBookButton.setImageResource(R.drawable.ic_add)
                binding.inputBarView.addBookButton.isEnabled = false
            }
            MainViewModel.LoadingState.EXTRACTING_DETAILS -> {
                binding.inputBarView.addBookButton.backgroundTintList = getTint(R.color.colorLoading)
                binding.inputBarView.addBookButton.setImageResource(R.drawable.ic_add)
                binding.inputBarView.addBookButton.isEnabled = false
            }
            MainViewModel.LoadingState.VALIDATING_DATA -> {
                binding.inputBarView.addBookButton.backgroundTintList = getTint(R.color.colorLoading)
                binding.inputBarView.addBookButton.setImageResource(R.drawable.ic_add)
                binding.inputBarView.addBookButton.isEnabled = false
            }
            MainViewModel.LoadingState.CHECKING_DUPLICATES -> {
                binding.inputBarView.addBookButton.backgroundTintList = getTint(R.color.colorLoading)
                binding.inputBarView.addBookButton.setImageResource(R.drawable.ic_add)
                binding.inputBarView.addBookButton.isEnabled = false
            }
            MainViewModel.LoadingState.SAVING_TO_DATABASE -> {
                binding.inputBarView.addBookButton.backgroundTintList = getTint(R.color.colorLoading)
                binding.inputBarView.addBookButton.setImageResource(R.drawable.ic_add)
                binding.inputBarView.addBookButton.isEnabled = false
            }
            MainViewModel.LoadingState.SUCCESS -> {
                binding.inputBarView.addBookButton.backgroundTintList = getTint(R.color.colorSuccess)
                binding.inputBarView.addBookButton.setImageResource(R.drawable.ic_check)
                binding.inputBarView.addBookButton.isEnabled = false
                
                // Success haptic feedback and button animation
                provideHapticFeedback(HapticType.SUCCESS)
                animateSuccessButton()
            }
        }
    }
    
    private fun updateDetailedStatus(detailedStatus: MainViewModel.DetailedStatus?) {
        val statusText = binding.inputBarView.addBookStatusText
        if (detailedStatus == null) {
            stopSavingToLibraryDotsAnimation()
            animateStatusTextChange(statusText, "", false)
        } else {
            val message = detailedStatus.subMessage ?: detailedStatus.message
            if (isSavingToLibraryStatus(message)) {
                startSavingToLibraryDotsAnimation(statusText)
            } else {
                stopSavingToLibraryDotsAnimation()
                animateStatusTextChange(statusText, message, true)
            }
        }
    }

    private fun startSavingToLibraryDotsAnimation(statusText: TextView) {
        if (savingToLibraryDotsJob?.isActive == true) return

        val needsFadeIn = statusText.visibility != View.VISIBLE
        statusText.setSavingToLibraryDots(0)
        if (needsFadeIn) {
            statusText.visibility = View.VISIBLE
            statusText.alpha = 0f
            SpringAnimation(statusText, DynamicAnimation.ALPHA, 1f).apply {
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }.start()
        }

        savingToLibraryDotsJob = lifecycleScope.launch {
            var dotCount = 0
            while (isActive) {
                delay(SAVING_TO_LIBRARY_DOT_INTERVAL_MS)
                dotCount = (dotCount + 1) % (SAVING_TO_LIBRARY_DOT_SLOTS + 1)
                statusText.setSavingToLibraryDots(dotCount)
            }
        }
    }

    private fun stopSavingToLibraryDotsAnimation() {
        savingToLibraryDotsJob?.cancel()
        savingToLibraryDotsJob = null
    }
    
    /**
     * Animate status text changes with Material 3 Expressive springs
     */
    private fun animateStatusTextChange(statusText: TextView, newText: String, visible: Boolean) {
        statusText.animateStatusTextChange(newText, visible)
    }
    
    /**
     * Animate the success button with a celebratory bounce
     */
    private fun animateSuccessButton() {
        binding.inputBarView.addBookButton.animateSuccessBounce()
    }
    
    private fun showSortOptions() {
        val currentSort = when (viewModel.currentSortOrder.value) {
            MainViewModel.SortOrder.TITLE_ASC -> "title"
            MainViewModel.SortOrder.TITLE_DESC -> "title"
            MainViewModel.SortOrder.AUTHOR_ASC -> "author"
            MainViewModel.SortOrder.AUTHOR_DESC -> "author"
            MainViewModel.SortOrder.DATE_ADDED_ASC -> "date"
            MainViewModel.SortOrder.DATE_ADDED_DESC -> "date"
            MainViewModel.SortOrder.PRICE_ASC -> "price"
            MainViewModel.SortOrder.PRICE_DESC -> "price"
            else -> "date"
        }
        
        val dialogFragment = SortDialogFragment.newInstance(currentSort)
        
        dialogFragment.setOnSortOptionSelectedListener { sortOption ->
            val sortOrder = when (sortOption) {
                "title" -> MainViewModel.SortOrder.TITLE_ASC
                "author" -> MainViewModel.SortOrder.AUTHOR_ASC
                "date" -> MainViewModel.SortOrder.DATE_ADDED_DESC
                "price" -> MainViewModel.SortOrder.PRICE_ASC
                else -> MainViewModel.SortOrder.DATE_ADDED_DESC
            }
            viewModel.setSortOrder(sortOrder)
        }
        
        dialogFragment.show(supportFragmentManager, "sort_dialog")
    }
    
    private fun showFilterOptions() {
        val dialogFragment = CategorySelectionDialogFragment.newInstance(
            title = getString(R.string.filter_by_category_title),
            bookTitle = getString(R.string.select_category_to_filter),
            currentCategoryName = viewModel.selectedCategory.value,
            showAllOption = true,
            selectedCategory = viewModel.selectedCategory.value
        )

        dialogFragment.setOnCategorySelectedListener { category ->
            viewModel.setSelectedCategory(category.name)
        }
        dialogFragment.setOnAllSelectedListener {
            viewModel.setSelectedCategory(null)
        }
        dialogFragment.setOnCategoryMenuClickListener { category ->
            CategoryOptionsDialogFragment
                .newInstance(category)
                .show(supportFragmentManager, "category_options_dialog")
        }
        dialogFragment.setOnValidateCategoryNameListener { categoryName ->
            viewModel.validateCategoryName(categoryName)
        }
        dialogFragment.setOnAddCategoryListener { categoryName, color ->
            viewModel.addCustomCategory(categoryName, color)
        }

        dialogFragment.show(supportFragmentManager, "category_selection_dialog")
    }
    
    private fun showSettings() {
        val dialog = SettingsDialogFragment()
        dialog.show(supportFragmentManager, "settings_dialog")
    }

    override fun onExportData() {
        exportData()
    }

    override fun onImportData() {
        importData()
    }

    override fun onClearData() {
        // TODO: Implement clear data logic
        Toast.makeText(this, getString(R.string.clear_data_not_implemented), Toast.LENGTH_SHORT).show()
    }
    
    private fun exportData() {
        Log.d("BookTracker", "MainActivity: Starting export data process")
        lifecycleScope.launch {
            try {
                // Get all books with stores
                Log.d("BookTracker", "MainActivity: Fetching all books with stores from ViewModel")
                val booksWithStores = withContext(Dispatchers.IO) {
                    viewModel.getAllBooksForExport()
                }
                
                Log.d("BookTracker", "MainActivity: Retrieved ${booksWithStores.size} books with stores")
                
                if (booksWithStores.isEmpty()) {
                    Log.w("BookTracker", "MainActivity: No books to export")
                    Toast.makeText(this@MainActivity, getString(R.string.no_books_to_export), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Generate CSV
                Log.d("BookTracker", "MainActivity: Generating CSV data")
                val csvData = withContext(Dispatchers.IO) {
                    dataExportService.exportToCSV(booksWithStores)
                }
                
                Log.d("BookTracker", "MainActivity: CSV generation completed, size: ${csvData.length} characters")
                
                // Create temporary file
                Log.d("BookTracker", "MainActivity: Creating temporary CSV file")
                val tempFile = withContext(Dispatchers.IO) {
                    createTempCsvFile(csvData)
                }
                
                Log.d("BookTracker", "MainActivity: Temporary file created: ${tempFile.absolutePath}")
                
                viewModel.setLastExportedData(csvData, tempFile)
                showExportOptionsDialog()
                return@launch
                // Removed: shareCsvFile(tempFile)
            } catch (e: Exception) {
                Log.e("BookTracker", "MainActivity: Export failed", e)
                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun importData() {
        Log.d("BookTracker", "MainActivity: Starting import data process - launching file picker")
        importLauncher.launch("*/*")
    }
    
    private fun handleImportFile(uri: Uri) {
        Log.d("BookTracker", "MainActivity: File selected for import: $uri")
        
        // Check MIME type first for robust validation
        val mimeType = contentResolver.getType(uri)
        if (mimeType != "text/csv" && mimeType != "text/comma-separated-values") {
            // Fallback to file name check for some file managers that don't provide proper MIME types
            val fileName = getFileNameFromUri(uri)
            if (fileName == null || !fileName.lowercase(Locale.ROOT).endsWith(".csv")) {
                Toast.makeText(this, "Please select a valid CSV file.", Toast.LENGTH_LONG).show()
                return
            }
        }
        lifecycleScope.launch {
            try {
                Log.d("BookTracker", "MainActivity: Parsing CSV file")
                val importResult = withContext(Dispatchers.IO) {
                    dataExportService.importFromCSV(uri)
                }
                
                Log.d("BookTracker", "MainActivity: CSV parsing completed, result type: ${importResult::class.simpleName}")
                
                when (importResult) {
                    is DataExportService.ImportResult.Success -> {
                        Log.d("BookTracker", "MainActivity: Import successful - Books: ${importResult.books.size}, Stores: ${importResult.stores.size}, Categories: ${importResult.categories.size}")
                        
                        Log.d("BookTracker", "MainActivity: Starting database import")
                        val result = withContext(Dispatchers.IO) {
                            viewModel.importData(importResult.books, importResult.stores, importResult.categories, importResult.storesByBookKey)
                        }
                        
                        Log.d("BookTracker", "MainActivity: Database import completed - Books imported: ${result.booksImported}, Stores imported: ${result.storesImported}, Categories imported: ${result.categoriesImported}, Duplicates merged: ${result.duplicatesMerged}")
                        
                        val message = buildString {
                            appendLine(getString(R.string.import_success))
                            appendLine(getString(R.string.books_imported, result.booksImported))
                            appendLine(getString(R.string.stores_imported, result.storesImported))
                            appendLine(getString(R.string.categories_imported, result.categoriesImported))
                            if (result.duplicatesMerged > 0) {
                                appendLine(getString(R.string.duplicates_merged, result.duplicatesMerged))
                            }
                        }
                        
                        Log.d("BookTracker", "MainActivity: Showing import result dialog with message: $message")
                        showImportResultDialogFragment(message)
                    }
                    is DataExportService.ImportResult.Error -> {
                        Log.e("BookTracker", "MainActivity: Import failed: ${importResult.message}")
                        Toast.makeText(this@MainActivity, "Import failed: ${importResult.message}", Toast.LENGTH_LONG).show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e("BookTracker", "MainActivity: Import process failed", e)
                Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Helper to get file name from Uri
    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = it.getString(idx)
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }
    
    private fun createTempCsvFile(csvData: String): File {
        Log.d("BookTracker", "MainActivity: Creating temporary CSV file with ${csvData.length} characters")
        
        // Create file in the app's private files directory for better sharing compatibility
        val tempFile = File(filesDir, "books_export_${System.currentTimeMillis()}.csv")
        
        try {
            FileWriter(tempFile).use { writer ->
                writer.write(csvData)
            }
            Log.d("BookTracker", "MainActivity: Temporary file created successfully: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e("BookTracker", "MainActivity: Error creating temp file", e)
            // Fallback to cache directory if files directory fails
            val fallbackFile = File(cacheDir, "books_export_${System.currentTimeMillis()}.csv")
            FileWriter(fallbackFile).use { writer ->
                writer.write(csvData)
            }
            Log.d("BookTracker", "MainActivity: Fallback file created in cache: ${fallbackFile.absolutePath}")
            return fallbackFile
        }
        
        return tempFile
    }
    
    @Suppress("SpellCheckingInspection")
    private fun shareCsvFile(file: File) {
        Log.d("BookTracker", "MainActivity: Sharing CSV file: ${file.absolutePath}")

        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            
            Log.d("BookTracker", "MainActivity: File URI created: $uri")

            // Resolve a robust MIME type; some apps ignore Intent.setType and query resolver instead
            val guessedMime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
            val mime = guessedMime ?: "text/csv"
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                // Set both data+type and EXTRA_STREAM for maximum compatibility
                setDataAndType(uri, mime)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.books_export_subject))
                putExtra(Intent.EXTRA_TEXT, "Books data exported from Books I Don't Need")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            }

            val chooser = Intent.createChooser(intent, getString(R.string.save_file)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            
            Log.d("BookTracker", "MainActivity: Launching share intent with MIME: $mime")
            exportLauncher.launch(chooser)
        } catch (e: Exception) {
            Log.e("BookTracker", "MainActivity: Error sharing CSV file", e)
            Toast.makeText(this, "Error sharing file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun handleExportResult(uri: Uri) {
        Log.d("BookTracker", "MainActivity: Export completed successfully, result URI: $uri")
        // Export completed successfully
        Toast.makeText(this, getString(R.string.export_success), Toast.LENGTH_SHORT).show()
        // Clean up temp file after export
        viewModel.clearLastExportedData()
    }


    
    private fun showImportResultDialogFragment(message: String) {
        val dialogFragment = ResultDialogFragment.newInstance(message)
        dialogFragment.show(supportFragmentManager, "import_result")
    }
    
    /**
     * Update filter button appearance based on whether a filter is applied
     */
    private fun updateFilterButtonAppearance(selectedCategory: String?) {
        val isFilterActive = selectedCategory != null
        val filterButton = binding.filterButton
        
        if (isFilterActive) {
            // Apply active filter styling
            filterButton.background = ContextCompat.getDrawable(this, R.drawable.filter_pill_background_active)
            filterButton.text = getString(R.string.filter_category_active)
        } else {
            // Apply default filter styling
            filterButton.background = ContextCompat.getDrawable(this, R.drawable.filter_pill_background)
            filterButton.text = getString(R.string.filter_category)
        }
    }
    /**
     * Initialize Material 3 Expressive motion springs and haptic feedback system
     */
    private fun initializeExpressiveMotion() {
        vibrator = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        
        // Setup spring animations for key UI elements
        setupButtonSpringAnimations()
        setupScrollSpringEffects()
    }
    
    /**
     * Setup spring-based animations for buttons following M3E guidelines
     */
    private fun setupButtonSpringAnimations() {
        val buttonActions = mapOf<View, () -> Unit>(
            binding.inputBarView.addBookButton to { addBookFromUrl() },
            binding.sortButton to { showSortOptions() },
            binding.filterButton to { showFilterOptions() },
            binding.cardViewToggleButton to { viewModel.toggleCardViewMode() },
            binding.settingsButton to { showSettings() }
        )

        buttonActions.forEach { (button, action) ->
            button.setOnClickListener { view ->
                provideHapticFeedback(HapticType.CONFIRMATION)
                view.animateClick()
                action()
            }
        }
    }
    
    /**
     * Setup spring effects for RecyclerView scrolling
     * Note: Scroll boundary haptic feedback is now handled in BookListFragment
     */
    private fun setupScrollSpringEffects() {
        // Scroll boundary haptic feedback moved to BookListFragment
    }
    

    

    

    
    // DialogFragment callback implementations
    // Remove onEditBook method

    // Book Options Dialog callbacks
    // (Removed onEditBookDetails, onChangeCategory, onUpdatePrices)

    // Store Edit Dialog callbacks
    // (Removed onEditBook and onDeleteStore)

    // Category Options Dialog callbacks
    // Legacy dialog callback stubs removed; handled via ViewModel events instead

    // Duplicate Check Dialog callbacks

    // Seller Selection Dialog callbacks

    // Confirmation Dialog callbacks
    // Removed onConfirm method - now handled by ViewModel directly

    // Result Dialog callbacks

    // Barnes & Noble Blocked Dialog callbacks

    // New DialogFragment methods
    // Helper to get categories with 'Uncategorized' at the front if missing
    private fun getCategoriesWithUncategorized(): List<String> {
        val categories = viewModel.allCategories.value?.map { it.name }?.toMutableList() ?: mutableListOf()
        val uncategorized = getString(R.string.uncategorized)
        if (!categories.contains(uncategorized)) {
            categories.add(0, uncategorized)
        }
        return categories
    }

    private fun showManualEntryDialogFragment(
        title: String = "",
        author: String = "",
        isbn: String = "",
        price: String = "",
        storeName: String = getString(R.string.manual_entry),
        storeUrl: String = "",
        category: String = getString(R.string.uncategorized)
    ) {
        val categories = getCategoriesWithUncategorized()
        val dialogFragment = ManualEntryDialogFragment.newInstance(
            title = title,
            author = author,
            isbn = isbn,
            price = price,
            storeName = storeName,
            storeUrl = storeUrl,
            category = category,
            categories = ArrayList(categories)
        )
        
        dialogFragment.show(supportFragmentManager, "manual_entry_dialog")
    }

    class MainViewModelFactory(private val repository: BookRepository, private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(repository, application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    // Add the dialog method:
    private fun showExportOptionsDialog() {
        ExportOptionsDialogFragment.newInstance().show(supportFragmentManager, "export_options_dialog")
    }

    override fun onSaveToDevice() {
        if (viewModel.lastExportedData.value == null) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "books_export.csv")
        }
        saveToDeviceLauncher.launch(intent)
    }

    override fun onShare() {
        val exportedData = viewModel.lastExportedData.value ?: return
        shareCsvFile(exportedData.tempFile)
        // Do not clear immediately; keep temp file available for the target app to read.
        // Cleanup happens after successful save, cancel, or on next export.
    }

    override fun onCancel() {
        // Clean up temp file if user cancels export
        viewModel.clearLastExportedData()
    }
    
    override fun onItemSelected(requestKey: String, which: Int) {
        if (requestKey == "add_book_options") {
            when (which) {
                0 -> binding.inputBarView.urlEditText.requestFocus()
                1 -> showManualEntryDialogFragment()
            }
        }
    }

    override fun onDialogCancelled(requestKey: String) {
        // No action needed for add book options cancel
    }

    /**
     * Extension function to get a ColorStateList from a color resource
     * Moved outside updateAddButtonState to avoid unnecessary object creation
     */
    private fun Context.getTint(colorRes: Int): ColorStateList {
        return when (colorRes) {
            R.color.colorPrimary -> {
                val color = com.google.android.material.color.MaterialColors.getColor(
                    this,
                    AppCompatR.attr.colorPrimary,
                    ContextCompat.getColor(this, R.color.md_theme_light_primary)
                )
                ColorStateList.valueOf(color)
            }
            else -> {
                ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
            }
        }
    }
} 

