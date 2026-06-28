package com.booktracker.booksidntneed.model

data class EditBookData(
    val book: Book,
    val store: BookStore,
    val title: String,
    val author: String,
    val isbn: String?,
    val price: Double?,
    val storeName: String?,
    val storeUrl: String?,
    val category: String?,
    val categories: List<String> = listOf("Uncategorized")
) 