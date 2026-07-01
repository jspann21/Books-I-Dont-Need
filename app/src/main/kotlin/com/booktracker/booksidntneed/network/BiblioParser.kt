package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

class BiblioParser : BookParser {
    companion object {
        private const val TAG = "BiblioParser"
    }

    override fun canParse(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host == "biblio.com" || host.endsWith(".biblio.com")
    }

    override fun getStoreName(): String = "Biblio"

    override fun parse(document: Document, url: String): ParsedBookInfo? {
        return try {
            Log.d(TAG, "Starting to parse Biblio URL: $url")

            val title = extractTitle(document, url)
            val author = extractAuthor(document)
            val isbn = extractISBN(document, url)
            val price = extractPrice(document)
            val coverImage = extractCoverImage(document)

            Log.d(TAG, "Extracted data - Title: '$title', Author: '$author', ISBN10: '${isbn.first}', ISBN13: '${isbn.second}', Price: $price")

            if (title.isNullOrBlank() || (author.isNullOrBlank() && isbn.first.isNullOrBlank() && isbn.second.isNullOrBlank())) {
                Log.w(TAG, "Missing required fields - Title: '$title', Author: '$author', ISBN: '$isbn'")
                return null
            }

            ParsedBookInfo(
                title = title,
                author = author,
                isbn10 = isbn.first,
                isbn13 = isbn.second,
                price = price,
                storeName = getStoreName(),
                storeUrl = url,
                coverImageUrl = coverImage
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Biblio parsing", e)
            null
        }
    }

    private fun extractTitle(document: Document, url: String): String? {
        val metaTitle = document.selectFirst("meta[property='og:title'], meta[name='twitter:title'], meta[name='title']")
            ?.attr("content")
            ?.trim()
        if (isUsableTitle(metaTitle)) {
            return cleanTitle(metaTitle!!)
        }

        val jsonTitle = extractStringFromStructuredData(document, "name")
        if (isUsableTitle(jsonTitle)) {
            return cleanTitle(jsonTitle!!)
        }

        val titleSelectors = listOf(
            "h1[itemprop='name']",
            "[itemprop='name']",
            ".book-title",
            ".product-title",
            "#product-title",
            "h1"
        )

        for (selector in titleSelectors) {
            val title = document.selectFirst(selector)?.text()?.trim()
            if (isUsableTitle(title)) {
                return cleanTitle(title!!)
            }
        }

        val pageTitle = document.title().trim()
        if (isUsableTitle(pageTitle)) {
            return cleanTitle(pageTitle)
        }

        return extractTitleFromUrl(url)
    }

    private fun isUsableTitle(title: String?): Boolean {
        if (title.isNullOrBlank() || title.length < 4) {
            return false
        }
        val lowerTitle = title.lowercase(Locale.ROOT)
        return !lowerTitle.contains("just a moment") &&
            !lowerTitle.contains("cloudflare") &&
            !lowerTitle.contains("access denied") &&
            !lowerTitle.contains("captcha")
    }

    private fun cleanTitle(title: String): String {
        val firstLineTitle = title
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: title

        return firstLineTitle
            .replace(Regex("\\s*[|\\-]\\s*Biblio(?:\\.com)?.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+by\\s+.+$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\((?:hardcover|paperback|softcover|mass market paperback|signed|first edition)\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ',', ':')
    }

    private fun extractTitleFromUrl(url: String): String? {
        val slug = Regex("/book/([^/?#]+)/d/").find(url)?.groupValues?.getOrNull(1)
            ?: return null

        val title = URLDecoder.decode(slug, "UTF-8")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return cleanTitle(title).takeIf { it.isNotBlank() }?.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private fun extractAuthor(document: Document): String? {
        val metaAuthor = document.selectFirst("meta[property='book:author'], meta[name='author'], meta[property='article:author']")
            ?.attr("content")
            ?.trim()
        if (isUsableAuthor(metaAuthor)) {
            return cleanAuthor(metaAuthor!!)
        }

        val jsonAuthor = extractAuthorFromStructuredData(document)
        if (isUsableAuthor(jsonAuthor)) {
            return cleanAuthor(jsonAuthor!!)
        }

        val authorSelectors = listOf(
            "[itemprop='author']",
            "a[href*='/author/']",
            "a[href*='author=']",
            ".author",
            ".book-author",
            ".byline",
            "[class*='author']"
        )

        for (selector in authorSelectors) {
            val author = document.selectFirst(selector)?.text()?.trim()
            if (isUsableAuthor(author)) {
                return cleanAuthor(author!!)
            }
        }

        return extractLabeledAuthor(document.text())?.let { cleanAuthor(it) }
    }

    private fun isUsableAuthor(author: String?): Boolean {
        if (author.isNullOrBlank() || author.length !in 2..120) {
            return false
        }
        val lowerAuthor = author.lowercase(Locale.ROOT)
        return !lowerAuthor.contains("biblio") &&
            !lowerAuthor.contains("seller") &&
            !lowerAuthor.contains("bookstore") &&
            !lowerAuthor.contains("customer") &&
            !lowerAuthor.contains("review") &&
            !lowerAuthor.contains("$")
    }

    private fun cleanAuthor(author: String): String {
        return author
            .replace("by ", "", ignoreCase = true)
            .replace("author:", "", ignoreCase = true)
            .replace("authors:", "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', ':', '-', '|')
    }

    private fun extractLabeledAuthor(text: String): String? {
        if (!text.contains("author", ignoreCase = true)) {
            return null
        }

        val patterns = listOf(
            Regex("(?i)\\bAuthor\\s*[:\\-]\\s*([^|\\n\\r;]+?)(?=\\s{2,}|\\b(?:ISBN|Publisher|Published|Edition|Binding|Condition|Book Details)\\b|$)"),
            Regex("(?i)\\bby\\s+([^|\\n\\r;]+?)(?=\\s{2,}|\\b(?:ISBN|Publisher|Published|Edition|Binding|Condition|Book Details)\\b|$)")
        )

        for (pattern in patterns) {
            val author = pattern.find(text)?.groupValues?.getOrNull(1)?.trim()
            if (isUsableAuthor(author)) {
                return author
            }
        }

        return null
    }

    private fun extractISBN(document: Document, url: String): Pair<String?, String?> {
        var isbn10: String?
        var isbn13: String?

        val urlIsbn = extractISBNFromText(url)
        isbn10 = urlIsbn.first
        isbn13 = urlIsbn.second

        val metaIsbn = document.selectFirst("meta[property='book:isbn'], meta[name='isbn'], meta[itemprop='isbn']")
            ?.attr("content")
            ?.trim()
        val parsedMeta = extractISBNFromText(metaIsbn.orEmpty())
        isbn10 = isbn10 ?: parsedMeta.first
        isbn13 = isbn13 ?: parsedMeta.second

        val jsonIsbn = extractStringFromStructuredData(document, "isbn")
            ?: extractStringFromStructuredData(document, "sku")
            ?: extractStringFromStructuredData(document, "gtin13")
        val parsedJson = extractISBNFromText(jsonIsbn.orEmpty())
        isbn10 = isbn10 ?: parsedJson.first
        isbn13 = isbn13 ?: parsedJson.second

        if (isbn10 == null || isbn13 == null) {
            val parsedText = extractISBNFromText(document.text())
            isbn10 = isbn10 ?: parsedText.first
            isbn13 = isbn13 ?: parsedText.second
        }

        return Pair(isbn10, isbn13)
    }

    private fun extractISBNFromText(text: String): Pair<String?, String?> {
        if (text.isBlank()) {
            return Pair(null, null)
        }

        val isbn13 = Regex("(?<!\\d)(97[89](?:[\\s-]?\\d){10})(?!\\d)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("[\\s-]"), "")
            ?.takeIf { isValidIsbn13(it) }
        val isbn10 = Regex("(?<!\\d)(\\d(?:[\\s-]?\\d){8}[\\dX])(?!\\d)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("[\\s-]"), "")
            ?.takeIf { isValidIsbn10(it) }
        return Pair(isbn10, isbn13)
    }

    private fun isValidIsbn10(isbn: String): Boolean {
        if (!isbn.matches(Regex("\\d{9}[\\dX]"))) {
            return false
        }
        val sum = isbn.mapIndexed { index, char ->
            val value = if (char == 'X') 10 else char.digitToInt()
            value * (10 - index)
        }.sum()
        return sum % 11 == 0
    }

    private fun isValidIsbn13(isbn: String): Boolean {
        if (!isbn.matches(Regex("\\d{13}"))) {
            return false
        }
        val sum = isbn.take(12).mapIndexed { index, char ->
            char.digitToInt() * if (index % 2 == 0) 1 else 3
        }.sum()
        val checkDigit = (10 - (sum % 10)) % 10
        return checkDigit == isbn.last().digitToInt()
    }

    private fun extractPrice(document: Document): Double? {
        val metaPrice = document.selectFirst("meta[property='product:price:amount'], meta[itemprop='price'], meta[name='price']")
            ?.attr("content")
            ?.let { extractPriceFromText(it) }
        if (metaPrice != null && metaPrice > 0) {
            return metaPrice
        }

        val jsonPrice = extractPriceFromStructuredData(document)
        if (jsonPrice != null && jsonPrice > 0) {
            return jsonPrice
        }

        val priceSelectors = listOf(
            "[itemprop='price']",
            ".price",
            ".book-price",
            ".product-price",
            "[class*='price']"
        )

        for (selector in priceSelectors) {
            for (element in document.select(selector)) {
                val price = extractPriceFromText(element.attr("content").ifBlank { element.text() })
                if (price != null && price > 0) {
                    return price
                }
            }
        }

        return document.select("*:containsOwn($), *:containsOwn(US$)")
            .asSequence()
            .mapNotNull { element ->
                val text = element.text()
                if (text.length <= 100) extractPriceFromText(text) else null
            }
            .firstOrNull { it > 0 }
    }

    private fun extractPriceFromText(text: String): Double? {
        val match = Regex("(?:US\\$|\\$)\\s*([0-9,]+(?:\\.[0-9]{2})?)").find(text)
            ?: Regex("\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]{2})?)\"?").find(text)
            ?: Regex("^\\s*([0-9,]+(?:\\.[0-9]{2})?)\\s*$").find(text)
        return match?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
    }

    private fun extractCoverImage(document: Document): String? {
        val metaImage = document.selectFirst("meta[property='og:image'], meta[name='twitter:image']")
            ?.attr("content")
            ?.trim()
        if (isValidImageUrl(metaImage)) {
            return makeAbsoluteUrl(metaImage!!, document)
        }

        val jsonImage = extractStringFromStructuredData(document, "image")
        if (isValidImageUrl(jsonImage)) {
            return makeAbsoluteUrl(jsonImage!!, document)
        }

        val imageSelectors = listOf(
            "[itemprop='image']",
            ".book-cover img",
            ".product-image img",
            ".cover img",
            "img[src*='pictures']",
            "img[src*='image']"
        )

        for (selector in imageSelectors) {
            val imageUrl = document.selectFirst(selector)?.let { element ->
                element.attr("content")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("srcset").substringBefore(" ") }
            }?.trim()
            if (isValidImageUrl(imageUrl)) {
                return makeAbsoluteUrl(imageUrl!!, document)
            }
        }

        return null
    }

    private fun isValidImageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) {
            return false
        }
        val lowerUrl = url.lowercase(Locale.ROOT)
        return !lowerUrl.contains("placeholder") &&
            !lowerUrl.contains("1x1") &&
            !lowerUrl.contains("logo") &&
            (lowerUrl.contains(".jpg") ||
                lowerUrl.contains(".jpeg") ||
                lowerUrl.contains(".png") ||
                lowerUrl.contains(".webp"))
    }

    private fun makeAbsoluteUrl(url: String, document: Document): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> runCatching { URL(URL(document.baseUri()), url).toString() }.getOrDefault(url)
        }
    }

    private fun extractStringFromStructuredData(document: Document, field: String): String? {
        val quotedField = Regex.escape(field)
        val patterns = listOf(
            Regex("\"$quotedField\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
            Regex("\"$quotedField\"\\s*:\\s*\\[\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        )

        for (script in document.select("script[type='application/ld+json'], script")) {
            val content = script.html()
            if (!content.contains(field, ignoreCase = true)) {
                continue
            }
            for (pattern in patterns) {
                val value = pattern.find(content)?.groupValues?.getOrNull(1)?.jsonClean()
                if (!value.isNullOrBlank()) {
                    return value
                }
            }
        }
        return null
    }

    private fun extractAuthorFromStructuredData(document: Document): String? {
        for (script in document.select("script[type='application/ld+json'], script")) {
            val content = script.html()
            if (!content.contains("author", ignoreCase = true)) {
                continue
            }

            val patterns = listOf(
                Regex("\"author\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
                Regex("\"author\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
                Regex("\"author\"\\s*:\\s*\\[\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
                Regex("\"authors\"\\s*:\\s*\\[\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
                val author = pattern.find(content)?.groupValues?.getOrNull(1)?.jsonClean()
                if (isUsableAuthor(author)) {
                    return author
                }
            }
        }
        return null
    }

    private fun extractPriceFromStructuredData(document: Document): Double? {
        val patterns = listOf(
            Regex("\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]{2})?)\"?", RegexOption.IGNORE_CASE),
            Regex("\"lowPrice\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]{2})?)\"?", RegexOption.IGNORE_CASE),
            Regex("\"salePrice\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]{2})?)\"?", RegexOption.IGNORE_CASE)
        )

        for (script in document.select("script[type='application/ld+json'], script")) {
            val content = script.html()
            if (!content.contains("price", ignoreCase = true)) {
                continue
            }

            for (pattern in patterns) {
                val price = pattern.find(content)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace(",", "")
                    ?.toDoubleOrNull()
                if (price != null && price > 0) {
                    return price
                }
            }
        }

        return null
    }

    private fun String.jsonClean(): String {
        return replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .trim()
    }
}
