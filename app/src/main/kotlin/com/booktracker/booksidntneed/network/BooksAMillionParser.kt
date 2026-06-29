package com.booktracker.booksidntneed.network

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

class BooksAMillionParser : BookParser {
    companion object {
        private const val TAG = "BooksAMillionParser"
    }

    override fun canParse(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host == "booksamillion.com" || host.endsWith(".booksamillion.com")
    }

    override fun getStoreName(): String = "Books-A-Million"

    override fun parse(document: Document, url: String): ParsedBookInfo? {
        return try {
            Log.d(TAG, "Starting to parse URL: $url")

            val productJson = extractProductDescriptionJson(document)
            val structuredData = structuredDataObjects(document)
            val title = extractTitle(document, url, productJson, structuredData)
            val author = extractAuthor(document, productJson, structuredData)
            val isbn = extractISBN(document, url, productJson, structuredData)
            val price = extractPrice(document, productJson, structuredData)
            val coverImage = extractCoverImage(document, productJson, structuredData)
            val publisher = extractDetail(document, "Publisher")
            val publishedDate = extractDetail(document, "Publish Date") ?: extractDetail(document, "Publication Date")
            val pages = extractDetail(document, "Page Count")?.toIntOrNull()

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

            bookInfo
        } catch (e: Exception) {
            Log.e(TAG, "Exception during parsing", e)
            null
        }
    }

    private fun extractTitle(
        document: Document,
        url: String,
        productJson: JSONObject?,
        structuredData: List<JSONObject>
    ): String? {
        productJson?.optString("item_title")?.takeIf { isUsableTitle(it) }?.let { return cleanTitle(it) }
        findJsonValue(structuredData, "name")?.takeIf { isUsableTitle(it) }?.let { return cleanTitle(it) }

        val metaTitle = document.selectFirst("meta[property='og:title'], meta[name='twitter:title'], meta[name='title']")
            ?.attr("content")
            ?.trim()
        if (isUsableTitle(metaTitle)) {
            return cleanTitle(metaTitle!!)
        }

        val titleSelectors = listOf(
            "#pdp-tl [data-cnstrc-item-name]",
            "h1",
            ".title",
            ".product-title",
            "[itemprop='name']"
        )
        for (selector in titleSelectors) {
            val element = document.selectFirst(selector)
            val title = element?.attr("data-cnstrc-item-name")?.ifBlank { element.text() }?.trim()
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
        if (title.isNullOrBlank() || title.length < 3) {
            return false
        }
        val lowerTitle = title.lowercase(Locale.ROOT)
        return !lowerTitle.contains("books-a-million") &&
            !lowerTitle.contains("booksamillion") &&
            !lowerTitle.contains("access denied") &&
            !lowerTitle.contains("forbidden") &&
            !lowerTitle.contains("captcha")
    }

    private fun cleanTitle(title: String): String {
        return title
            .jsonClean()
            .replace(Regex("\\s*:\\s*[^:]+\\s*:\\s*97[89]\\d{10}\\s*$"), "")
            .replace(Regex("\\s+by\\s+.+$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*[|\\-]\\s*Books-A-Million.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ',', ':')
    }

    private fun extractTitleFromUrl(url: String): String? {
        val pathParts = runCatching { URL(RequestStrategyUtils.ensureHttps(url)).path.split("/") }.getOrDefault(emptyList())
        val titleSlug = pathParts.getOrNull(2)?.takeUnless { it.matches(Regex("\\d{10,13}")) } ?: return null
        val decoded = URLDecoder.decode(titleSlug, "UTF-8")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return decoded.split(" ")
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }
            .takeIf { isUsableTitle(it) }
    }

    private fun extractAuthor(
        document: Document,
        productJson: JSONObject?,
        structuredData: List<JSONObject>
    ): String? {
        productJson?.optJSONArray("item_author")?.let { authors ->
            val names = (0 until authors.length())
                .mapNotNull { authors.optString(it).takeIf { name -> isUsableAuthor(name) }?.let(::cleanAuthor) }
                .distinct()
            if (names.isNotEmpty()) {
                return names.joinToString(", ")
            }
        }

        extractAuthorFromStructuredData(structuredData)?.takeIf { isUsableAuthor(it) }?.let { return cleanAuthor(it) }

        val metaAuthor = document.selectFirst("meta[property='book:author'], meta[name='author'], meta[property='article:author']")
            ?.attr("content")
            ?.trim()
        if (isUsableAuthor(metaAuthor)) {
            return cleanAuthor(metaAuthor!!)
        }

        val ogTitle = document.selectFirst("meta[property='og:title']")?.attr("content").orEmpty()
        extractAuthorFromTitle(ogTitle)?.let { return cleanAuthor(it) }
        extractAuthorFromTitle(document.title())?.let { return cleanAuthor(it) }

        val authorSelectors = listOf(
            "a[href*='/search'][href*='author']",
            "a[href*='/p/'][href*='Author']",
            ".author a",
            ".contributor a",
            "[itemprop='author']"
        )
        for (selector in authorSelectors) {
            val author = document.selectFirst(selector)?.text()?.trim()
            if (isUsableAuthor(author)) {
                return cleanAuthor(author!!)
            }
        }

        return null
    }

    private fun extractAuthorFromTitle(title: String): String? {
        if (title.isBlank()) {
            return null
        }

        val colonParts = title.split(":").map { it.trim() }.filter { it.isNotBlank() }
        if (colonParts.size >= 3 && colonParts.last().replace(Regex("[\\s-]"), "").matches(Regex("97[89]\\d{10}"))) {
            return colonParts[colonParts.lastIndex - 1].takeIf { isUsableAuthor(it) }
        }

        val byMatch = Regex("(?i)\\bby\\s+([^|\\n\\r]+)$").find(title)
        return byMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { isUsableAuthor(it) }
    }

    private fun isUsableAuthor(author: String?): Boolean {
        if (author.isNullOrBlank() || author.length !in 2..160) {
            return false
        }
        val lowerAuthor = author.lowercase(Locale.ROOT)
        return !lowerAuthor.contains("books-a-million") &&
            !lowerAuthor.contains("booksamillion") &&
            !lowerAuthor.contains("hardcover") &&
            !lowerAuthor.contains("paperback") &&
            !lowerAuthor.contains("isbn") &&
            !lowerAuthor.contains("$")
    }

    private fun cleanAuthor(author: String): String {
        return author
            .jsonClean()
            .replace("by ", "", ignoreCase = true)
            .replace("author:", "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', ':', '-', '|')
    }

    private fun extractISBN(
        document: Document,
        url: String,
        productJson: JSONObject?,
        structuredData: List<JSONObject>
    ): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null

        val urlIsbn = extractISBNFromText(url)
        isbn10 = urlIsbn.first
        isbn13 = urlIsbn.second

        val jsonIsbn = productJson?.optString("sku").takeUnless { it.isNullOrBlank() }
            ?: findJsonValue(structuredData, "isbn")
            ?: findJsonValue(structuredData, "sku")
            ?: findJsonValue(structuredData, "mpn")
        val parsedJson = extractISBNFromText(jsonIsbn.orEmpty())
        isbn10 = isbn10 ?: parsedJson.first
        isbn13 = isbn13 ?: parsedJson.second

        val metaIsbn = document.selectFirst("meta[property='product:retailer_item_id'], meta[property='product:item_group_id'], meta[property='book:isbn'], meta[name='isbn']")
            ?.attr("content")
            ?.trim()
        val parsedMeta = extractISBNFromText(metaIsbn.orEmpty())
        isbn10 = isbn10 ?: parsedMeta.first
        isbn13 = isbn13 ?: parsedMeta.second

        val detailIsbn13 = extractDetail(document, "ISBN-13")
        val parsedDetail13 = extractISBNFromText(detailIsbn13.orEmpty())
        isbn10 = isbn10 ?: parsedDetail13.first
        isbn13 = isbn13 ?: parsedDetail13.second

        val detailIsbn10 = extractDetail(document, "ISBN-10")
        val parsedDetail10 = extractISBNFromText(detailIsbn10.orEmpty())
        isbn10 = isbn10 ?: parsedDetail10.first
        isbn13 = isbn13 ?: parsedDetail10.second

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

    private fun extractPrice(
        document: Document,
        productJson: JSONObject?,
        structuredData: List<JSONObject>
    ): Double? {
        val priceData = productJson?.optJSONObject("price_data")
        listOf("our_price", "online_price", "retail_price", "club_price", "store_price").forEach { key ->
            priceData?.optString(key)?.let { extractPriceFromText(it) }?.takeIf { it > 0 }?.let { return it }
        }

        document.selectFirst("[data-cnstrc-item-price]")?.attr("data-cnstrc-item-price")
            ?.let { extractPriceFromText(it) }
            ?.takeIf { it > 0 }
            ?.let { return it }

        extractPriceFromStructuredData(structuredData)?.takeIf { it > 0 }?.let { return it }

        document.selectFirst("meta[property='product:price:amount'], meta[itemprop='price'], meta[name='price']")
            ?.attr("content")
            ?.let { extractPriceFromText(it) }
            ?.takeIf { it > 0 }
            ?.let { return it }

        val priceSelectors = listOf(
            ".titleDetailBTNatc",
            ".price",
            ".formatPrice",
            "[class*='price']",
            "*:containsOwn($)"
        )
        for (selector in priceSelectors) {
            for (element in document.select(selector).take(12)) {
                val text = element.attr("content").ifBlank { element.ownText() }.ifBlank { element.text() }
                if (text.length <= 100) {
                    extractPriceFromText(text)?.takeIf { it > 0 }?.let { return it }
                }
            }
        }

        return null
    }

    private fun extractPriceFromText(text: String): Double? {
        val match = Regex("\\$?\\s*([0-9,]+(?:\\.[0-9]{2})?)").find(text)
            ?: Regex("\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]{2})?)\"?", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
    }

    private fun extractCoverImage(
        document: Document,
        productJson: JSONObject?,
        structuredData: List<JSONObject>
    ): String? {
        productJson?.optString("item_img_path")?.takeIf { isValidImageUrl(it) }?.let { return makeAbsoluteUrl(it, document) }
        findJsonValue(structuredData, "image")?.takeIf { isValidImageUrl(it) }?.let { return makeAbsoluteUrl(it, document) }

        val metaImage = document.selectFirst("meta[property='og:image'], meta[name='twitter:image']")
            ?.attr("content")
            ?.trim()
        if (isValidImageUrl(metaImage)) {
            return makeAbsoluteUrl(metaImage!!, document)
        }

        val imageSelectors = listOf(
            "#pdpImg img",
            "img[src*='covers.booksamillion.com']",
            "img[src*='covers'][src*='bam']",
            "[itemprop='image']"
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
        return !lowerUrl.contains("1x1") &&
            !lowerUrl.contains("placeholder") &&
            !lowerUrl.contains("logo") &&
            (lowerUrl.contains(".jpg") ||
                lowerUrl.contains(".jpeg") ||
                lowerUrl.contains(".png") ||
                lowerUrl.contains(".webp"))
    }

    private fun makeAbsoluteUrl(url: String, document: Document): String {
        return when {
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> runCatching { URL(URL(document.baseUri()), url).toString() }.getOrDefault(url)
        }.replace("&amp;", "&")
    }

    private fun extractProductDescriptionJson(document: Document): JSONObject? {
        val rawJson = document.selectFirst("#product_description_json")?.html()?.trim()
            ?: return null

        return runCatching { JSONObject(rawJson) }
            .onFailure { Log.d(TAG, "Could not parse product_description_json: ${it.message}") }
            .getOrNull()
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
            }.onFailure { Log.d(TAG, "Could not parse JSON-LD: ${it.message}") }
        }

        return objects
    }

    private fun findJsonValue(objects: List<JSONObject>, field: String): String? {
        for (obj in objects) {
            findJsonValue(obj, field)?.let { return it }
        }
        return null
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

    private fun extractAuthorFromStructuredData(objects: List<JSONObject>): String? {
        for (obj in objects) {
            val author = obj.opt("author") ?: obj.opt("authors")
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
        return null
    }

    private fun extractPriceFromStructuredData(objects: List<JSONObject>): Double? {
        for (obj in objects) {
            obj.optString("price").takeIf { it.isNotBlank() }?.let { extractPriceFromText(it) }?.takeIf { it > 0 }?.let { return it }
            val offers = obj.opt("offers")
            when (offers) {
                is JSONObject -> offers.optString("price").takeIf { it.isNotBlank() }?.let { extractPriceFromText(it) }?.takeIf { it > 0 }?.let { return it }
                is JSONArray -> {
                    for (index in 0 until offers.length()) {
                        val offer = offers.optJSONObject(index) ?: continue
                        offer.optString("price").takeIf { it.isNotBlank() }?.let { extractPriceFromText(it) }?.takeIf { it > 0 }?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun extractDetail(document: Document, label: String): String? {
        val detailText = document.selectFirst("#details-section")?.text().orEmpty()
            .ifBlank { document.text() }
        if (!detailText.contains(label, ignoreCase = true)) {
            return null
        }

        val pattern = Regex(
            "(?i)\\b${Regex.escape(label)}\\s*:?\\s*([^|\\n\\r;]+?)(?=\\s{2,}|\\b(?:ISBN-13|ISBN-10|Publisher|Publish Date|Publication Date|Dimensions|Shipping Weight|Page Count|Related Categories)\\b|$)"
        )
        return pattern.find(detailText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim(' ', ',', ':', '-', '|')
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.jsonClean(): String {
        return replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }
}
