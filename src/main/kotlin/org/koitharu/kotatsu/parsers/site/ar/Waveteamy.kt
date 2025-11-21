package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("WAVETEAMY", "Waveteamy", "ar")
internal class Waveteamy(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.WAVETEAMY, 50) {

    override val configKeyDomain = ConfigKey.Domain("waveteamy.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Remove Content-Encoding header from POST requests to avoid gzip compression issues
        if (originalRequest.method == "POST") {
            val newRequest = originalRequest.newBuilder()
                .removeHeader("Content-Encoding")
                .build()
            return chain.proceed(newRequest)
        }

        return chain.proceed(originalRequest)
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.UPDATED)

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrBlank()) {
            return getSearch(filter.query, page)
        }

        val url = "https://$domain/wapi/hanout/v1/series/releases-web"
        val formBody = mapOf(
            "page" to page.toString(),
            "limit" to "50"
        )

        val response = webClient.httpPost(url.toHttpUrl(), formBody).parseJson()
        val chapters = response.getJSONArray("chapters")

        return (0 until chapters.length()).map { i ->
            val item = chapters.getJSONObject(i)
            parseMangaFromList(item)
        }
    }

    private suspend fun getSearch(query: String, page: Int): List<Manga> {
        // Search doesn't seem to support pagination based on the user request, 
        // but we'll handle the first page at least.
        if (page > 1) return emptyList()

        val token = fetchToken()
        val url = "https://$domain/wapi/hanout/v1/series/search-work-site"
        val formBody = mapOf(
            "token" to token,
            "keyValue" to query
        )

        val response = webClient.httpPost(url.toHttpUrl(), formBody).parseJson()
        
        if (!response.getBoolean("success")) {
            return emptyList()
        }

        val data = response.getJSONArray("data")
        return (0 until data.length()).map { i ->
            val item = data.getJSONObject(i)
            // Search result format might be slightly different, let's adapt
            // Based on user request: "workName", "workImage", "postId"
            Manga(
                id = generateUid(item.getLong("postId")),
                title = item.getString("workName"),
                url = "/series/${item.getLong("postId")}",
                publicUrl = "https://$domain/series/${item.getLong("postId")}",
                coverUrl = resolveCover(item.getString("workImage")),
                source = source,
                rating = RATING_UNKNOWN,
                altTitles = emptySet(),
                contentRating = ContentRating.SAFE,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                largeCoverUrl = null,
                description = null,
                chapters = null,
            )
        }
    }

    private var cachedToken: String? = null

    private suspend fun fetchToken(): String {
        cachedToken?.let { return it }

        // Try to fetch from the layout file as suggested
        // We first need to find the layout file name from the main page
        val mainPage = webClient.httpGet("https://$domain").parseHtml()
        val scriptSrc = mainPage.select("script[src*='layout-']").attr("src")
        
        if (scriptSrc.isNotEmpty()) {
            val scriptContent = webClient.httpGet(scriptSrc.toAbsoluteUrl(domain)).body?.string() ?: ""
            val tokenMatch = Regex("""["']?token["']?\s*:\s*["']([^"']+)["']""").find(scriptContent)
            if (tokenMatch != null) {
                val token = tokenMatch.groupValues[1]
                cachedToken = token
                return token
            }
        }
        
        // Fallback: try to find it in any script on the main page if the specific layout file logic fails
        // or if the user provided URL was just an example and the hash changes.
        // The user provided: https://waveteamy.com/_next/static/chunks/app/layout-2d9af0783ea921ab.js
        // We can try to regex the main page for the layout chunk URL.
        
        throw ParseException("Could not find search token", "https://$domain")
    }

    private fun parseMangaFromList(json: JSONObject): Manga {
        val id = json.getLong("postId")
        val title = json.getString("title")
        val cover = json.getString("imageUrl")
        val rating = json.optString("ratingValue", "0").toFloatOrNull()?.div(2f) ?: RATING_UNKNOWN // 10 point scale to 5
        val statusVal = json.optInt("statusValue")
        val state = when (statusVal) {
            0 -> MangaState.ONGOING
            1 -> MangaState.FINISHED
            2 -> MangaState.PAUSED // Assuming 2 might be paused/stopped based on context
            else -> MangaState.ONGOING
        }
        
        val genres = json.optString("genre").split(",").mapNotNull { 
            val trimmed = it.trim()
            if (trimmed.isNotEmpty()) MangaTag(key = trimmed, title = trimmed, source = source) else null
        }.toSet()

        return Manga(
            id = generateUid(id),
            title = title,
            url = "/series/$id",
            publicUrl = "https://$domain/series/$id",
            coverUrl = resolveCover(cover),
            source = source,
            rating = rating,
            altTitles = emptySet(),
            contentRating = ContentRating.SAFE,
            tags = genres,
            state = state,
            authors = emptySet(),
            largeCoverUrl = null,
            description = null,
            chapters = null,
        )
    }

    private fun resolveCover(path: String): String {
        if (path.startsWith("http")) return path

        // Use wcloud.site for series/projects/users paths as they are served from CDN
        if (path.startsWith("series/") || path.startsWith("projects/") || path.startsWith("users/")) {
            return "https://wcloud.site/$path"
        }

        // For other paths, use the main domain
        return "https://$domain/$path"
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfterLast("/")
        val url = "https://$domain/series/$id"
        val doc = webClient.httpGet(url).parseHtml()

        val scriptContent = doc.select("script").map { it.html() }
            .find { it.contains("mangaData") && it.contains("chaptersData") }
            ?: throw ParseException("Failed to find manga data", url)

        val jsonStr = extractJson(scriptContent, url)
        val json = JSONObject(jsonStr)

        val mangaData = json.getJSONObject("mangaData")
        val title = mangaData.getString("name")
        val cover = mangaData.getString("cover")
        val description = mangaData.optString("story").takeIf { it.isNotEmpty() && it != "null" }
        val statusVal = mangaData.optInt("status")
        val state = when (statusVal) {
            0 -> MangaState.ONGOING
            1 -> MangaState.FINISHED
            else -> MangaState.ONGOING
        }

        val authors = mutableSetOf<String>()
        mangaData.optString("author").takeIf { it.isNotEmpty() && it != "null" }?.let { authors.add(it) }
        mangaData.optString("artist").takeIf { it.isNotEmpty() && it != "null" }?.let { authors.add(it) }

        val genres = mangaData.optJSONArray("genre")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val g = arr.optString(i)
                if (g.isNotEmpty() && g != "null") MangaTag(key = g, title = g, source = source) else null
            }.toSet()
        } ?: emptySet()

        val postId = mangaData.getLong("postId")
        val chaptersData = json.optJSONArray("chaptersData")
        val chapters = if (chaptersData != null) {
            (0 until chaptersData.length()).mapNotNull { i ->
                try {
                    val ch = chaptersData.getJSONObject(i)
                    val chId = ch.getInt("id")
                    val chNum = ch.optDouble("chapter", 0.0).toFloat()
                    val chDate = ch.getString("postTime")
                    val chTitle = ch.optString("title").takeIf { it.isNotEmpty() && it != "null" }

                    MangaChapter(
                        id = generateUid(chId.toLong()),
                        title = chTitle,
                        number = chNum,
                        volume = 0,
                        url = "/series/$postId/${chNum.toInt()}",
                        uploadDate = parseDate(chDate),
                        source = source,
                        scanlator = null,
                        branch = null
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } else {
            emptyList()
        }

        return manga.copy(
            title = title,
            description = description,
            coverUrl = resolveCover(cover),
            state = state,
            authors = authors,
            tags = genres,
            chapters = chapters.reversed(),
        )
    }

    private fun extractJson(script: String, url: String): String {
        // The script format is usually: self.__next_f.push([1,"5:[...{\"mangaData\":{...
        // We need to find the object containing "mangaData" and "chaptersData" (or "currentChapter" for pages)
        
        // Find the start of the object we care about
        val keyIndex = if (script.contains("\"mangaData\":")) {
            script.indexOf("\"mangaData\":")
        } else {
            script.indexOf("\"currentChapter\":")
        }
        
        if (keyIndex == -1) throw ParseException("Required data not found", url)

        // Walk backwards to find the opening brace of this object
        var startIndex = -1
        var braceBalance = 0
        // We look for the closest '{' before the key that is at the same nesting level
        // But since we are inside a string in the push array, it's tricky.
        // However, the user snippet shows: ... "5:[[\"$\",\"script\",null,{\"type\":\"application/ld+json\",\"dangerouslySetInnerHTML\":{\"__html\":\"$19\"}}],[\"$\",\"$L1a\",null,{\"initialData\":{\"workInfo\":...
        // The data is often inside an escaped string.
        
        // Let's try a simpler approach: extract the whole JSON string containing the key, then parse it.
        // The data seems to be inside `self.__next_f.push` arguments.
        // It's often in the form: `self.__next_f.push([1,"...string with json..."])`
        
        // Let's try to find the opening brace of the object containing the key.
        for (i in keyIndex downTo 0) {
            if (script[i] == '{') {
                startIndex = i
                break
            }
        }
        
        if (startIndex == -1) throw ParseException("Could not find start of JSON object", url)

        // Now find the matching closing brace
        var braceCount = 0
        var inString = false
        var escape = false
        
        for (i in startIndex until script.length) {
            val c = script[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') braceCount++
                if (c == '}') {
                    braceCount--
                    if (braceCount == 0) {
                        val jsonCandidate = script.substring(startIndex, i + 1)
                        // Unescape the JSON string if it was inside a string literal
                        // The Next.js format often has the JSON stringified inside the push array
                        // e.g. "5:[...]"
                        // But here we are extracting just the object part `{...}`. 
                        // If it was escaped like `{\"mangaData\":...}`, our substring will be `{\"mangaData\":...}`
                        // We need to unescape it.
                        return jsonCandidate.replace("\\\"", "\"").replace("\\\\", "\\")
                    }
                }
            }
        }
        throw ParseException("Could not find end of JSON object", url)
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L

        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss+00:00",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
        )

        for (pattern in formats) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                // Try next format
            }
        }
        
        try {
            val datePart = dateStr.substring(0, 10)
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(datePart)?.time ?: 0L
        } catch (e: Exception) {
            return 0L
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(url).parseHtml()
        
        // Parse Next.js hydration data for pages
        // Look for script containing "currentChapter" and "images"
        val scriptContent = doc.select("script").map { it.html() }
            .find { it.contains("currentChapter") && it.contains("images") }
            ?: throw ParseException("Failed to find chapter data", url)

        val jsonStr = extractJson(scriptContent, url)
        val json = JSONObject(jsonStr)
        
        // The structure based on user log: {"initialData":{..., "currentChapter":{"id":..., "images":[...]}}}
        // Or sometimes directly the object with currentChapter if we extracted that deep.
        
        val currentChapter = if (json.has("currentChapter")) {
            json.getJSONObject("currentChapter")
        } else if (json.has("initialData")) {
            json.getJSONObject("initialData").getJSONObject("currentChapter")
        } else {
            // Fallback: maybe we extracted the currentChapter object itself?
            if (json.has("images")) json else throw ParseException("Could not find currentChapter object", url)
        }
        
        val images = currentChapter.getJSONArray("images")
        
        return (0 until images.length()).map { i ->
            val imagePath = images.getString(i)
            val imageUrl = if (imagePath.startsWith("http")) {
                imagePath
            } else {
                "https://wcloud.site/$imagePath"
            }
            
            MangaPage(
                id = generateUid("${chapter.id}-$i"),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }
}
