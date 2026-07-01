package com.booktracker.booksidntneed.network

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

class BarnesAndNobleParser : BookParser {
    companion object {
        private const val TAG = "BarnesAndNobleParser"
    }

    override fun canParse(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host == "barnesandnoble.com" ||
            host.endsWith(".barnesandnoble.com") ||
            host == "bn.com" ||
            host.endsWith(".bn.com")
    }

    override fun getStoreName(): String = "Barnes & Noble"

    override fun parse(document: Document, url: String): ParsedBookInfo? {
        return try {
            Log.d(TAG, "Starting to parse URL: $url")

            val title = extractTitle(document, url)
            val author = extractAuthor(document)
            val isbn = extractISBN(document, url)
            val price = extractPrice(document)
            val coverImage = extractCoverImage(document)
            val publisher = extractPublisher(document)
            val publishedDate = extractPublishedDate(document)
            val pages = extractPages(document)

            Log.d(TAG, "Extracted data - Title: '$title', Author: '$author', ISBN10: '${isbn.first}', ISBN13: '${isbn.second}', Price: $price")

            val bookInfo = ParsedBookInfo(
                title = title,
                author = author,
                isbn10 = isbn.first,
                isbn13 = isbn.second,
                price = price,
                storeName = getStoreName(),
                storeUrl = url,
                coverImageUrl = coverImage,
                publisher = publisher,
                publishedDate = publishedDate,
                pages = pages
            )

            if (!bookInfo.isValid()) {
                Log.w(TAG, "Missing required fields - Title: '$title', Author: '$author', ISBN: '$isbn'")
                return null
            }

            Log.d(TAG, "Successfully created ParsedBookInfo")
            bookInfo
        } catch (e: Exception) {
            Log.e(TAG, "Exception during parsing", e)
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
            "h1.product__title",
            ".product__title",
            "h1.pdp-product-title",
            "[data-testid='product-title']",
            ".product-title h1",
            "h1[data-testid*='title']",
            ".pdp-product-name h1",
            ".product-info h1",
            ".book-title",
            "h1.product-name",
            "[data-automation-id='product-title']",
            "h1"
        )

        for (selector in titleSelectors) {
            val title = document.selectFirst(selector)?.text()?.trim()
            if (isUsableTitle(title)) {
                Log.d(TAG, "Found title with selector '$selector': '$title'")
                return cleanTitle(title!!)
            }
        }

        val pageTitle = document.title().trim()
        if (isUsableTitle(pageTitle)) {
            return cleanTitle(pageTitle)
        }

        val urlTitle = extractTitleFromUrl(url)
        if (isUsableTitle(urlTitle)) {
            Log.d(TAG, "Using title from URL slug: '$urlTitle'")
            return urlTitle
        }

        Log.w(TAG, "No title found")
        return null
    }

    private fun isUsableTitle(title: String?): Boolean {
        if (title.isNullOrBlank() || title.length < 4) {
            return false
        }
        val lowerTitle = title.lowercase(Locale.ROOT)
        return !lowerTitle.contains("barnes & noble's online bookstore") &&
            lowerTitle != "barnes & noble" &&
            !lowerTitle.contains("access denied") &&
            !lowerTitle.contains("captcha") &&
            !lowerTitle.contains("robot") &&
            !lowerTitle.contains("page not found")
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace("&amp;", "&")
            .replace(Regex("\\s*[|\\-]\\s*Barnes\\s*&\\s*Noble(?:®|\\(R\\))?.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+by\\s+.+$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\((?:hardcover|paperback|ebook|nook book|mass market paperback|board book)\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ',', ':')
    }

    private fun extractTitleFromUrl(url: String): String? {
        val slug = Regex("/w/([^/?#]+)/").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("/w/([^/?#]+)").find(url)?.groupValues?.getOrNull(1)
            ?: return null

        val title = URLDecoder.decode(slug, "UTF-8")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return title
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
            .let { cleanTitle(it) }
            .takeIf { it.isNotBlank() }
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
            ".product__contributor a",
            ".product-contributor a",
            "[data-testid='author-list'] a",
            ".contributors a",
            ".product-details .author",
            ".pdp-contributor-list a",
            "[data-testid='contributors'] a",
            ".author-link",
            ".book-contributors a",
            ".contributor-name",
            "[data-automation-id='author'] a",
            "a[href*='contributorName=']",
            "a[href*='/author/']",
            ".by-author a"
        )

        for (selector in authorSelectors) {
            val authors = document.select(selector)
                .map { it.text().trim() }
                .filter { isUsableAuthor(it) }
                .map { cleanAuthor(it) }
                .distinct()

            if (authors.isNotEmpty()) {
                val author = authors.joinToString(", ")
                Log.d(TAG, "Found author with selector '$selector': '$author'")
                return author
            }
        }

        val authorTextSelectors = listOf(
            ".product__contributor",
            ".contributors",
            ".pdp-contributor-list",
            ".product-details",
            ".book-details"
        )

        for (selector in authorTextSelectors) {
            val author = extractAuthorFromText(document.selectFirst(selector)?.text().orEmpty())
            if (isUsableAuthor(author)) {
                Log.d(TAG, "Found author from '$selector' text: '$author'")
                return cleanAuthor(author!!)
            }
        }

        val pageAuthor = extractAuthorFromText(document.title())
        if (isUsableAuthor(pageAuthor)) {
            return cleanAuthor(pageAuthor!!)
        }

        Log.w(TAG, "No author found")
        return null
    }

    private fun extractAuthorFromText(text: String): String? {
        if (text.isBlank()) {
            return null
        }

        val patterns = listOf(
            Regex("(?i)\\bBy\\s+([^|\\n\\r]+?)(?=\\s*(?:Format|Hardcover|Paperback|NOOK|\\$|$))"),
            Regex("(?i)\\bAuthor\\s*[:\\-]\\s*([^|\\n\\r;]+?)(?=\\s{2,}|\\b(?:Publisher|ISBN|Format|Pages|Publication Date)\\b|$)")
        )

        for (pattern in patterns) {
            val author = pattern.find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(Regex("\\s*,\\s*$"), "")
                ?.trim()
            if (isUsableAuthor(author)) {
                return author
            }
        }

        return null
    }

    private fun isUsableAuthor(author: String?): Boolean {
        if (author.isNullOrBlank() || author.length !in 2..160) {
            return false
        }
        val lowerAuthor = author.lowercase(Locale.ROOT)
        return !lowerAuthor.contains("barnes") &&
            !lowerAuthor.contains("noble") &&
            !lowerAuthor.contains("customer") &&
            !lowerAuthor.contains("review") &&
            !lowerAuthor.contains("hardcover") &&
            !lowerAuthor.contains("paperback") &&
            !lowerAuthor.contains("$") &&
            !lowerAuthor.contains("isbn")
    }

    private fun cleanAuthor(author: String): String {
        return author
            .replace("&amp;", "&")
            .replace("By ", "", ignoreCase = true)
            .replace("Author: ", "", ignoreCase = true)
            .replace("Authors: ", "", ignoreCase = true)
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', ':', '-', '|')
    }

    private fun extractPrice(document: Document): Double? {
        Log.d(TAG, "Starting price extraction")

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
            ".product__prices .product-price",
            ".product-price",
            "[data-testid='current-price']",
            ".current-price",
            ".price-current",
            ".pdp-price .current",
            ".price .current",
            "[data-testid='price']",
            ".product-price .current",
            ".price-info .price",
            ".book-price",
            "[data-automation-id='price']",
            "[class*='price']"
        )

        for (selector in priceSelectors) {
            val elements = document.select(selector)
            Log.d(TAG, "Trying price selector '$selector', found ${elements.size} elements")

            for (element in elements.take(8)) {
                val text = element.attr("content").ifBlank { element.text() }.trim()
                if (text.length > 120) {
                    continue
                }

                val price = extractPriceFromText(text)
                if (price != null && price > 0) {
                    Log.d(TAG, "Successfully extracted price: $price")
                    return price
                }
            }
        }

        return document.select("*:containsOwn($)")
            .asSequence()
            .mapNotNull { element ->
                val text = element.ownText().trim()
                if (text.length <= 60) extractPriceFromText(text) else null
            }
            .firstOrNull { it > 0 }
    }

    private fun extractPriceFromText(priceText: String): Double? {
        val match = Regex("\\$\\s*([0-9,]+(?:\\.[0-9]{2})?)").find(priceText)
            ?: Regex("\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]{2})?)\"?", RegexOption.IGNORE_CASE).find(priceText)
            ?: Regex("^\\s*([0-9,]+(?:\\.[0-9]{2})?)\\s*$").find(priceText)
        return match?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
    }

    private fun extractISBN(document: Document, url: String): Pair<String?, String?> {
        Log.d(TAG, "Starting ISBN extraction")

        val urlIsbn = extractISBNFromText(url)
        var isbn10 = urlIsbn.first
        var isbn13 = urlIsbn.second

        val isbnMetaSelectors = listOf(
            "meta[property='book:isbn']",
            "meta[name='isbn']",
            "meta[itemprop='isbn']",
            "[itemprop='isbn']"
        )

        for (selector in isbnMetaSelectors) {
            val candidate = document.selectFirst(selector)?.let { element ->
                element.attr("content").ifBlank { element.text() }
            }
            val parsed = extractISBNFromText(candidate.orEmpty())
            isbn10 = isbn10 ?: parsed.first
            isbn13 = isbn13 ?: parsed.second
        }

        val jsonIsbn = extractStringFromStructuredData(document, "isbn")
            ?: extractStringFromStructuredData(document, "gtin13")
            ?: extractStringFromStructuredData(document, "sku")
        val parsedJson = extractISBNFromText(jsonIsbn.orEmpty())
        isbn10 = isbn10 ?: parsedJson.first
        isbn13 = isbn13 ?: parsedJson.second

        if (isbn10 == null || isbn13 == null) {
            val imageIsbn = extractISBNFromText(extractCoverImage(document).orEmpty())
            isbn10 = isbn10 ?: imageIsbn.first
            isbn13 = isbn13 ?: imageIsbn.second
        }

        if (isbn10 == null || isbn13 == null) {
            val documentIsbn = extractISBNFromText(document.text())
            isbn10 = isbn10 ?: documentIsbn.first
            isbn13 = isbn13 ?: documentIsbn.second
        }

        Log.d(TAG, "Final ISBN results - ISBN-10: '$isbn10', ISBN-13: '$isbn13'")
        return Pair(isbn10, isbn13)
    }

    private fun extractISBNFromText(text: String): Pair<String?, String?> {
        if (text.isBlank()) {
            return Pair(null, null)
        }

        val isbn13 = Regex("(?<!\\d)(97[89](?:[\\s-]?\\d){10})(?!\\d)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("[\\s-]"), "")
            ?.takeIf { isValidIsbn13(it) }

        val isbn10 = Regex("(?<!\\d)(\\d(?:[\\s-]?\\d){8}[\\dX])(?!\\d)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("[\\s-]"), "")
            ?.uppercase(Locale.ROOT)
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

    private fun extractCoverImage(document: Document): String? {
        Log.d(TAG, "Starting image extraction")

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
            "img[itemprop='image']",
            "[data-testid='product-image'] img",
            ".product-image img",
            ".pdp-image img",
            ".pdp-product-image img",
            ".cover-image img",
            ".book-cover img",
            ".product-photo img",
            "[data-automation-id='product-image'] img",
            "img[alt*='cover']",
            "img[alt*='book']",
            "img[src*='cdn.shopify.com']",
            "img[src*='pimages']"
        )

        for (selector in imageSelectors) {
            val elements = document.select(selector)
            Log.d(TAG, "Trying image selector '$selector', found ${elements.size} elements")

            for (element in elements) {
                val imageUrl = element.attr("content")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("srcset").substringBefore(" ") }
                    .trim()

                if (isValidImageUrl(imageUrl)) {
                    val absoluteUrl = makeAbsoluteUrl(imageUrl, document)
                    Log.d(TAG, "Found valid image URL: $absoluteUrl")
                    return absoluteUrl
                }
            }
        }

        Log.w(TAG, "No valid image found")
        return null
    }

    private fun isValidImageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) {
            return false
        }
        val lowerUrl = url.lowercase(Locale.ROOT)
        return !lowerUrl.contains("1x1") &&
            !lowerUrl.contains("placeholder") &&
            !lowerUrl.contains("grey-box.png") &&
            !lowerUrl.contains("logo") &&
            (lowerUrl.contains(".jpg") ||
                lowerUrl.contains(".jpeg") ||
                lowerUrl.contains(".png") ||
                lowerUrl.contains(".webp"))
    }

    private fun makeAbsoluteUrl(url: String, document: Document): String {
        val absoluteUrl = when {
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> runCatching { URL(URL(document.baseUri()), url).toString() }.getOrDefault(url)
        }
        return absoluteUrl.replace("&amp;", "&")
    }

    private fun extractStringFromStructuredData(document: Document, field: String): String? {
        for (jsonObject in structuredDataObjects(document)) {
            findJsonValue(jsonObject, field)?.let { return it }
        }
        return extractStringFromScripts(document, field)
    }

    private fun extractAuthorFromStructuredData(document: Document): String? {
        for (jsonObject in structuredDataObjects(document)) {
            val author = jsonObject.opt("author") ?: jsonObject.opt("authors")
            when (author) {
                is String -> if (isUsableAuthor(author)) return author
                is JSONObject -> {
                    val name = author.optString("name")
                    if (isUsableAuthor(name)) return name
                }
                is JSONArray -> {
                    val names = (0 until author.length()).mapNotNull { index ->
                        when (val item = author.opt(index)) {
                            is String -> item.takeIf { isUsableAuthor(it) }
                            is JSONObject -> item.optString("name").takeIf { isUsableAuthor(it) }
                            else -> null
                        }
                    }.distinct()
                    if (names.isNotEmpty()) {
                        return names.joinToString(", ")
                    }
                }
            }
        }

        return extractAuthorFromScripts(document)
    }

    private fun extractPriceFromStructuredData(document: Document): Double? {
        for (jsonObject in structuredDataObjects(document)) {
            val directPrice = jsonObject.optString("price").takeIf { it.isNotBlank() }?.let { extractPriceFromText(it) }
            if (directPrice != null && directPrice > 0) {
                return directPrice
            }

            when (val offers = jsonObject.opt("offers")) {
                is JSONObject -> {
                    val price = offers.optString("price").takeIf { it.isNotBlank() }?.let { extractPriceFromText(it) }
                    if (price != null && price > 0) return price
                }
                is JSONArray -> {
                    for (index in 0 until offers.length()) {
                        val offer = offers.optJSONObject(index) ?: continue
                        val price = offer.optString("price").takeIf { it.isNotBlank() }?.let { extractPriceFromText(it) }
                        if (price != null && price > 0) return price
                    }
                }
            }
        }

        return null
    }

    private fun extractPublisher(document: Document): String? {
        return extractStringFromStructuredData(document, "publisher")
            ?.takeIf { it.length in 2..120 }
            ?: extractLabeledDetail(document.text(), "Publisher")
    }

    private fun extractPublishedDate(document: Document): String? {
        return extractStringFromStructuredData(document, "datePublished")
            ?.takeIf { it.length in 4..40 }
            ?: extractLabeledDetail(document.text(), "Publication date")
            ?: extractLabeledDetail(document.text(), "Published")
    }

    private fun extractPages(document: Document): Int? {
        val jsonPages = extractStringFromStructuredData(document, "numberOfPages")
            ?.toIntOrNull()
        if (jsonPages != null && jsonPages > 0) {
            return jsonPages
        }

        return extractLabeledDetail(document.text(), "Pages")
            ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            ?.takeIf { it > 0 }
    }

    private fun extractLabeledDetail(text: String, label: String): String? {
        if (text.isBlank() || !text.contains(label, ignoreCase = true)) {
            return null
        }

        val pattern = Regex(
            "(?i)\\b${Regex.escape(label)}\\s*[:\\-]?\\s*([^|\\n\\r;]+?)(?=\\s{2,}|\\b(?:Publisher|Publication date|Published|Pages|ISBN|Format|Language|Product dimensions|Item weight)\\b|$)"
        )
        return pattern.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim(' ', ',', ':', '-', '|')
            ?.takeIf { it.isNotBlank() }
    }

    private fun structuredDataObjects(document: Document): List<JSONObject> {
        val objects = mutableListOf<JSONObject>()

        for (script in document.select("script[type='application/ld+json']")) {
            val content = script.html().trim()
            if (content.isBlank()) {
                continue
            }

            runCatching {
                if (content.startsWith("[")) {
                    val array = JSONArray(content)
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let(objects::add)
                    }
                } else {
                    objects.add(JSONObject(content))
                }
            }.onFailure { error ->
                Log.d(TAG, "Could not parse JSON-LD: ${error.message}")
            }
        }

        return objects
    }

    private fun findJsonValue(jsonObject: JSONObject, field: String): String? {
        if (jsonObject.has(field)) {
            when (val value = jsonObject.opt(field)) {
                is String -> return value.jsonClean().takeIf { it.isNotBlank() }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        when (val item = value.opt(index)) {
                            is String -> return item.jsonClean().takeIf { it.isNotBlank() }
                            is JSONObject -> item.optString("name").jsonClean().takeIf { it.isNotBlank() }?.let { return it }
                        }
                    }
                }
                is JSONObject -> value.optString("name").jsonClean().takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        for (key in jsonObject.keys()) {
            when (val nested = jsonObject.opt(key)) {
                is JSONObject -> findJsonValue(nested, field)?.let { return it }
                is JSONArray -> {
                    for (index in 0 until nested.length()) {
                        val item = nested.optJSONObject(index) ?: continue
                        findJsonValue(item, field)?.let { return it }
                    }
                }
            }
        }

        return null
    }

    private fun extractStringFromScripts(document: Document, field: String): String? {
        val pattern = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        for (script in document.select("script")) {
            val value = pattern.find(script.html())?.groupValues?.getOrNull(1)?.jsonClean()
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    private fun extractAuthorFromScripts(document: Document): String? {
        val patterns = listOf(
            Regex("\"author\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
            Regex("\"author\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
            Regex("\"authors\"\\s*:\\s*\\[\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        )

        for (script in document.select("script")) {
            val content = script.html()
            for (pattern in patterns) {
                val author = pattern.find(content)?.groupValues?.getOrNull(1)?.jsonClean()
                if (isUsableAuthor(author)) {
                    return author
                }
            }
        }

        return null
    }

    private fun String.jsonClean(): String {
        return replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }
}
