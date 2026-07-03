package com.booktracker.booksidntneed.ui.dialog

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.ui.MainViewModel
import com.booktracker.booksidntneed.utils.DataExportService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.util.Locale

class DataHandler(
    private val activity: AppCompatActivity,
    private val viewModel: MainViewModel,
    private val dataExportService: DataExportService,
    private val exportLauncher: ActivityResultLauncher<Intent>,
    private val importLauncher: ActivityResultLauncher<String>,
    private val saveToDeviceLauncher: ActivityResultLauncher<Intent>
) {
    fun exportData(onShowExportOptions: () -> Unit) {
        activity.lifecycleScope.launch {
            try {
                val booksWithStores = withContext(Dispatchers.IO) {
                    viewModel.getAllBooksForExport()
                }
                if (booksWithStores.isEmpty()) {
                    Toast.makeText(activity, "No books to export", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val csvData = withContext(Dispatchers.IO) {
                    dataExportService.exportToCSV(booksWithStores)
                }
                val tempFile = withContext(Dispatchers.IO) {
                    createTempCsvFile(csvData)
                }
                viewModel.setLastExportedData(csvData, tempFile)
                onShowExportOptions()
            } catch (e: Exception) {
                Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun importData() {
        importLauncher.launch("*/*")
    }

    fun handleImportFile(uri: Uri, onShowImportResult: (String) -> Unit) {
        if (viewModel.loadingState.value != MainViewModel.LoadingState.IDLE) {
            Toast.makeText(activity, "An import is already in progress.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val contentResolver = activity.contentResolver
        activity.lifecycleScope.launch {
            try {
                val mimeType = contentResolver.getType(uri)
                if (mimeType != "text/csv" && mimeType != "text/comma-separated-values") {
                    val fileName = getFileNameFromUri(uri)
                    if (fileName == null || !fileName.lowercase(Locale.ROOT).endsWith(".csv")) {
                        Toast.makeText(activity, "Please select a valid CSV file.", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
                val importResult = withContext(Dispatchers.IO) {
                    dataExportService.importFromCSV(uri)
                }
                when (importResult) {
                    is DataExportService.ImportResult.Success -> {
                        val preview = withContext(Dispatchers.IO) {
                            viewModel.previewImport(importResult.books, importResult.categories, importResult.storesByBookKey)
                        }
                        showImportPreviewDialog(preview) {
                            activity.lifecycleScope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        viewModel.importData(importResult.books, importResult.stores, importResult.categories, importResult.storesByBookKey)
                                    }
                                    onShowImportResult(buildImportResultMessage(result))
                                } catch (e: Exception) {
                                    Toast.makeText(activity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    is DataExportService.ImportResult.Error -> {
                        Toast.makeText(activity, "Import failed: ${importResult.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(activity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                // No need to reset loading here; ViewModel handles loadingState
            }
        }
    }

    private fun showImportPreviewDialog(preview: MainViewModel.ImportPreview, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.import_preview_title)
            .setMessage(buildImportPreviewMessage(preview))
            .setPositiveButton(R.string.continue_import) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildImportPreviewMessage(preview: MainViewModel.ImportPreview): String {
        return buildString {
            appendLine(activity.getString(R.string.books_will_be_added, preview.booksToAdd))
            appendLine(activity.getString(R.string.books_will_be_merged, preview.booksToMerge))
            appendLine(activity.getString(R.string.store_listings_will_be_added, preview.storesToAdd))
            appendLine(activity.getString(R.string.store_listings_will_be_updated, preview.storesToUpdate))
            if (preview.storesUnchanged > 0) {
                appendLine(activity.getString(R.string.store_listings_will_be_unchanged, preview.storesUnchanged))
            }
            appendLine(activity.getString(R.string.categories_will_be_added, preview.categoriesToAdd))
            appendLine()
            append(activity.getString(R.string.import_preview_footer))
        }
    }

    private fun buildImportResultMessage(result: MainViewModel.ImportResult): String {
        return buildString {
            appendLine(activity.getString(R.string.import_success))
            appendLine(activity.getString(R.string.books_imported, result.booksImported))
            appendLine(activity.getString(R.string.store_listings_added, result.storesImported))
            if (result.storesUpdated > 0) {
                appendLine(activity.getString(R.string.store_listings_updated, result.storesUpdated))
            }
            if (result.storesUnchanged > 0) {
                appendLine(activity.getString(R.string.store_listings_unchanged, result.storesUnchanged))
            }
            appendLine(activity.getString(R.string.categories_imported, result.categoriesImported))
            if (result.duplicatesMerged > 0) {
                appendLine(activity.getString(R.string.duplicates_merged, result.duplicatesMerged))
            }
        }
    }

    fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = activity.contentResolver.query(uri, null, null, null, null)
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

    fun createTempCsvFile(csvData: String): File {
        val tempFile = File(activity.cacheDir, "books_export_${System.currentTimeMillis()}.csv")
        FileWriter(tempFile).use { writer ->
            writer.write(csvData)
        }
        return tempFile
    }

    fun shareCsvFile(file: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Books Export")
            putExtra(Intent.EXTRA_TEXT, "Books data exported from Books I Don't Need")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        exportLauncher.launch(Intent.createChooser(intent, activity.getString(R.string.save_file)))
    }

    fun handleExportResult(uri: Uri) {
        Toast.makeText(activity, activity.getString(R.string.export_success), Toast.LENGTH_SHORT).show()
    }
} 
