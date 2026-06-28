package com.booktracker.booksidntneed.model

data class ManualEntryData(
    val title: String = "",
    val author: String = "",
    val isbn: String? = null,
    val price: Double? = null,
    val storeName: String? = null,
    val storeUrl: String? = null,
    val category: String? = null,
    val categories: List<String> = listOf("Uncategorized")
) 