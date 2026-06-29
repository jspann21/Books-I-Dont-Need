package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.Locale

class WalmartParser : BookParser {
    companion object {
        private const val TAG = "WalmartParser"
    }

    override fun canParse(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host == "walmart.com" || host.endsWith(".walmart.com")
    }

    override fun getStoreName(): String = "Walmart"

    override fun parse(document: Document, url: String): ParsedBookInfo? {
        return try {
            Log.d(TAG, "Starting to parse Walmart URL: $url")

            val title = extractTitle(document, url)
            val isbn = extractISBN(document, url)
            val author = extractAuthor(document, isbn)
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
            Log.e(TAG, "Exception during Walmart parsing", e)
            null
        }
    }

    private fun extractTitle(document: Document, url: String): String? {
        val metaSelectors = listOf(
            "meta[property='og:title']",
            "meta[name='twitter:title']",
            "meta[name='title']"
        )

        for (selector in metaSelectors) {
            val title = document.selectFirst(selector)?.attr("content")?.trim()
            if (isUsableTitle(title)) {
                return cleanTitle(title!!)
            }
        }

        val jsonTitle = extractProductNameFromStructuredData(document)
        if (isUsableTitle(jsonTitle)) {
            return cleanTitle(jsonTitle!!)
        }

        val titleSelectors = listOf(
            "h1[itemprop='name']",
            "h1[data-testid*='title']",
            "[data-testid='product-title']",
            "[data-automation-id='product-title']",
            ".prod-ProductTitle",
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
        return !lowerTitle.contains("robot or human") &&
            !lowerTitle.contains("blocked") &&
            !lowerTitle.contains("captcha")
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\s*[|\\-]\\s*Walmart\\.com.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*,?\\s*\\((?:hardcover|paperback|mass market paperback|board book|spiral-bound|ebook|kindle|audio cd|audiobook)\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b97[89]\\d{10}\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ',', ':')
    }

    private fun extractTitleFromUrl(url: String): String? {
        val productSlug = Regex("/ip/([^/?#]+)/\\d+").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("/ip/([^/?#]+)").find(url)?.groupValues?.getOrNull(1)
            ?: return null

        val decodedSlug = URLDecoder.decode(productSlug, "UTF-8")
        val title = decodedSlug
            .replace(Regex("(?i)-?97[89]\\d{10}-?"), "-")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return cleanTitle(title).takeIf { it.isNotBlank() }
    }

    private fun extractAuthor(document: Document, isbn: Pair<String?, String?>): String? {
        val authorMetaSelectors = listOf(
            "meta[property='book:author']",
            "meta[name='author']",
            "meta[property='article:author']"
        )

        for (selector in authorMetaSelectors) {
            val author = document.selectFirst(selector)?.attr("content")?.trim()
            if (isUsableAuthor(author)) {
                return cleanAuthor(author!!)
            }
        }

        val jsonAuthor = extractAuthorFromScripts(document)
        if (isUsableAuthor(jsonAuthor)) {
            return cleanAuthor(jsonAuthor!!)
        }

        val featureAuthor = extractAuthorFromFeatureText(document)
        if (isUsableAuthor(featureAuthor)) {
            return cleanAuthor(featureAuthor!!)
        }

        val authorSelectors = listOf(
            "[itemprop='author']",
            "[data-testid*='author']",
            "[data-automation-id*='author']",
            "a[href*='/author/']",
            "a[href*='author=']",
            ".author",
            ".byline"
        )

        for (selector in authorSelectors) {
            val author = document.selectFirst(selector)?.text()?.trim()
            if (isUsableAuthor(author)) {
                return cleanAuthor(author!!)
            }
        }

        return null
    }

    private fun isUsableAuthor(author: String?): Boolean {
        if (author.isNullOrBlank() || author.length !in 2..100) {
            return false
        }
        val lowerAuthor = author.lowercase(Locale.ROOT)
        return !lowerAuthor.contains("walmart") &&
            !lowerAuthor.contains("customer") &&
            !lowerAuthor.contains("review") &&
            !lowerAuthor.contains("seller") &&
            lowerAuthor !in setOf(
                "sun", "sunday",
                "mon", "monday",
                "tue", "tues", "tuesday",
                "wed", "wednesday",
                "thu", "thur", "thurs", "thursday",
                "fri", "friday",
                "sat", "saturday"
            )
    }

    private fun cleanAuthor(author: String): String {
        return author
            .replace("by ", "", ignoreCase = true)
            .replace("author:", "", ignoreCase = true)
            .replace("authors:", "", ignoreCase = true)
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\s*:\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractISBN(document: Document, url: String): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null

        val urlIsbn = extractISBNFromText(url)
        isbn10 = urlIsbn.first
        isbn13 = urlIsbn.second

        val isbnMetaSelectors = listOf(
            "meta[property='book:isbn']",
            "meta[name='isbn']",
            "meta[property='product:isbn']"
        )

        for (selector in isbnMetaSelectors) {
            val candidate = document.selectFirst(selector)?.attr("content")
            val parsed = extractISBNFromText(candidate.orEmpty())
            isbn10 = isbn10 ?: parsed.first
            isbn13 = isbn13 ?: parsed.second
        }

        val jsonIsbn = extractStringFromScripts(document, "isbn")
            ?: extractStringFromScripts(document, "gtin13")
            ?: extractStringFromScripts(document, "gtin")
        val parsedJsonIsbn = extractISBNFromText(jsonIsbn.orEmpty())
        isbn10 = isbn10 ?: parsedJsonIsbn.first
        isbn13 = isbn13 ?: parsedJsonIsbn.second

        if (isbn10 == null || isbn13 == null) {
            val documentIsbn = extractISBNFromText(document.text())
            isbn10 = isbn10 ?: documentIsbn.first
            isbn13 = isbn13 ?: documentIsbn.second
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
        val metaPrice = document.selectFirst("meta[property='product:price:amount'], meta[itemprop='price']")
            ?.attr("content")
            ?.toDoubleOrNull()
        if (metaPrice != null && metaPrice > 0) {
            return metaPrice
        }

        val jsonPrice = extractPriceFromScripts(document)
        if (jsonPrice != null && jsonPrice > 0) {
            return jsonPrice
        }

        val priceSelectors = listOf(
            "[itemprop='price']",
            "[data-testid*='price']",
            "[data-automation-id*='price']",
            ".price-characteristic",
            ".price",
            ".prod-PriceHero"
        )

        for (selector in priceSelectors) {
            val priceElements = document.select(selector)
            for (element in priceElements) {
                val price = extractPriceFromText(element.attr("content").ifBlank { element.text() })
                if (price != null && price > 0) {
                    return price
                }
            }
        }

        return document.select("*:containsOwn($)")
            .asSequence()
            .mapNotNull { element ->
                val text = element.text()
                if (text.length <= 80) extractPriceFromText(text) else null
            }
            .firstOrNull { it > 0 }
    }

    private fun extractPriceFromText(text: String): Double? {
        val match = Regex("\\$\\s*([0-9,]+(?:\\.[0-9]{2})?)").find(text)
            ?: Regex("\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]{2})?)\"?").find(text)
        return match?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
    }

    private fun extractCoverImage(document: Document): String? {
        val metaImage = document.selectFirst("meta[property='og:image'], meta[name='twitter:image']")
            ?.attr("content")
            ?.trim()
        if (isValidImageUrl(metaImage)) {
            return metaImage
        }

        val jsonImage = extractImageFromStructuredData(document)
        if (isValidImageUrl(jsonImage)) {
            return jsonImage
        }

        val imageSelectors = listOf(
            "[data-testid='product-image'] img",
            "[data-automation-id='product-image'] img",
            "img[alt*='book']",
            "img[alt*='cover']",
            "img[src*='i5.walmartimages.com']"
        )

        for (selector in imageSelectors) {
            val imageUrl = document.selectFirst(selector)?.let { element ->
                element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("srcset") }
            }?.trim()
            if (isValidImageUrl(imageUrl)) {
                return imageUrl
            }
        }

        return null
    }

    private fun isValidImageUrl(url: String?): Boolean {
        return !url.isNullOrBlank() &&
            url.startsWith("http") &&
            !url.contains("placeholder", ignoreCase = true) &&
            !url.contains("1x1") &&
            (url.contains(".jpg", ignoreCase = true) ||
                url.contains(".jpeg", ignoreCase = true) ||
                url.contains(".png", ignoreCase = true) ||
                url.contains(".webp", ignoreCase = true))
    }

    private fun extractAuthorFromScripts(document: Document): String? {
        val scripts = document.select("script")
        for (script in scripts) {
            val content = script.html()
            val patterns = listOf(
                Regex("\"author\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"author\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"authors\"\\s*:\\s*\\[\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"")
            )
            for (pattern in patterns) {
                val value = pattern.find(content)?.groupValues?.getOrNull(1)
                if (isUsableAuthor(value)) {
                    return value
                }
            }
        }
        return null
    }

    private fun extractAuthorFromFeatureText(document: Document): String? {
        val candidateTexts = buildList {
            addAll(document.select("[data-testid*='feature'], [data-testid*='spec'], [data-testid*='attribute']").map { it.text() })
            addAll(document.select("[class*='feature'], [class*='spec'], [class*='attribute']").map { it.text() })
            add(document.select("main, #maincontent, body").firstOrNull()?.text().orEmpty())
        }

        for (text in candidateTexts) {
            val author = extractLabeledAuthor(text)
            if (isUsableAuthor(author)) {
                return author
            }
        }

        return null
    }

    private fun extractLabeledAuthor(text: String): String? {
        if (text.isBlank() || !text.contains("author", ignoreCase = true)) {
            return null
        }

        val patterns = listOf(
            Regex("(?i)\\bAuthor\\s*[:\\-]\\s*([^|\\n\\r;]+?)(?=\\s{2,}|\\b(?:Publisher|ISBN|Book Format|Format|Language|Pages|Publication Date|Features|Brand|Assembled Product)\\b|$)"),
            Regex("(?i)\\bAuthor\\s+([^|\\n\\r;]+?)(?=\\s{2,}|\\b(?:Publisher|ISBN|Book Format|Format|Language|Pages|Publication Date|Features|Brand|Assembled Product)\\b|$)")
        )

        for (pattern in patterns) {
            val author = pattern.find(text)?.groupValues?.getOrNull(1)?.trim(' ', ',', ':', '-', '|')
            if (isUsableAuthor(author)) {
                return author
            }
        }

        return null
    }

    private fun extractProductNameFromStructuredData(document: Document): String? {
        for (script in document.select("script[type='application/ld+json']")) {
            val content = script.html()
            if (!content.contains("\"@type\"", ignoreCase = true) ||
                !(content.contains("Product", ignoreCase = true) || content.contains("Book", ignoreCase = true))
            ) {
                continue
            }

            val value = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
                .find(content)
                ?.groupValues
                ?.getOrNull(1)
            if (isUsableTitle(value) && !value.equals("Walmart", ignoreCase = true)) {
                return value
            }
        }
        return null
    }

    private fun extractImageFromStructuredData(document: Document): String? {
        for (script in document.select("script[type='application/ld+json']")) {
            val content = script.html()
            if (!content.contains("\"@type\"", ignoreCase = true) ||
                !(content.contains("Product", ignoreCase = true) || content.contains("Book", ignoreCase = true))
            ) {
                continue
            }

            val value = Regex("\"image\"\\s*:\\s*\"([^\"]+)\"")
                .find(content)
                ?.groupValues
                ?.getOrNull(1)
            if (isValidImageUrl(value)) {
                return value
            }
        }
        return null
    }

    private fun extractStringFromScripts(document: Document, field: String): String? {
        val scripts = document.select("script")
        val pattern = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
        for (script in scripts) {
            val value = pattern.find(script.html())?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    private fun extractPriceFromScripts(document: Document): Double? {
        val pricePatterns = listOf(
            Regex("\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]{2})?)\"?"),
            Regex("\"currentPrice\"\\s*:\\s*\\{[^}]*\"price\"\\s*:\\s*([0-9,]+(?:\\.[0-9]{2})?)"),
            Regex("\"priceString\"\\s*:\\s*\"\\$([0-9,]+(?:\\.[0-9]{2})?)\"")
        )

        for (script in document.select("script")) {
            val content = script.html()
            for (pattern in pricePatterns) {
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
}
