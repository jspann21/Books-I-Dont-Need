package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document

interface BookParser {
    fun canParse(url: String): Boolean
    fun parse(document: Document, url: String): ParsedBookInfo?
    fun getStoreName(): String
}

object BookParserFactory {
    private val parsers = listOf(
        AmazonParser(),
        BarnesAndNobleParser(),
        BookOutletParser(),
        BetterWorldBooksParser(),
        WalmartParser(),
        CrosswayParser(),
        GoogleBooksParser(),
        ChristianBookParser(),
        BiblestoreParser(),
        BiblioParser(),
        AbeBooksParser(),
        EbayParser(),
        ThriftBooksParser(),
        HalfPriceBooksParser(),
        LogosParser(),
        WorldOfBooksParser(),
        ValoreParser(),
        GenericParser() // Fallback parser - should be last
    )
    
    fun getParser(url: String): BookParser {
        Log.d("BookTracker", "BookParserFactory: Finding parser for URL: $url")
        
        val selectedParser = parsers.find { parser ->
            val canParse = parser.canParse(url)
            Log.d("BookTracker", "BookParserFactory: ${parser.javaClass.simpleName}.canParse('$url') = $canParse")
            canParse
        } ?: GenericParser()
        
        Log.d("BookTracker", "BookParserFactory: Selected parser: ${selectedParser.javaClass.simpleName}")
        return selectedParser
    }

}
