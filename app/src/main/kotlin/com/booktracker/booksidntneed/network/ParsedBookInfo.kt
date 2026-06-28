package com.booktracker.booksidntneed.network

data class ParsedBookInfo(
    val title: String? = null,
    val author: String? = null,
    val isbn10: String? = null,
    val isbn13: String? = null,
    val price: Double? = null,
    val currency: String = "USD",
    val storeName: String? = null,
    val storeUrl: String,
    val coverImageUrl: String? = null,
    val language: String? = null,
    val pages: Int? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val availability: String? = null
) {
    fun isValid(): Boolean {
        // Require title at minimum
        // Author is preferred but not required if we have an ISBN (some stores don't show author)
        return !title.isNullOrBlank() && 
               (!author.isNullOrBlank() || hasISBN())
    }
    
    fun hasISBN(): Boolean {
        return !isbn10.isNullOrBlank() || !isbn13.isNullOrBlank()
    }

}