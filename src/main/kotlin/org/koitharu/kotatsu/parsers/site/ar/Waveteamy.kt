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
        return "https://$domain/$path" // Based on user request example: series/751/cover/... -> https://waveteamy.com/series/...
        // Wait, user said: "https://wcloud.site/series/..." in one example, but "https://waveteamy.com/images/place_holder" in JS.
        // The JS function l(e) says: if starts with projects/series/users -> s.k.r2domain + t
        // We don't know s.k.r2domain easily without parsing more JS.
        // However, the user provided example: "https://wcloud.site/series/572/cover/01102641604.webp"
        // Let's try to use wcloud.site if it looks like a relative path, or fall back to domain.
        // Actually, let's just use the domain for now, or better, check if we can find the r2domain.
        // For now, I'll use a safe bet or what the user showed.
        // User showed: "imageUrl":"series/751/cover/14191006424.jpg"
        // And later: <img src="https://wcloud.site/series/572/cover/01102641604.webp"
        // So it seems `wcloud.site` is the CDN.
        // But let's stick to what we can construct safely. If I use absolute URL it works.
        // If I use relative, the app might handle it if I prepend the domain.
        // Let's try prepending `https://wcloud.site/` for now if it matches the pattern, otherwise `https://waveteamy.com/`.
        
        if (path.startsWith("series/") || path.startsWith("projects/") || path.startsWith("users/")) {
            return "https://wcloud.site/$path"
        }
        return "https://$domain/$path"
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfterLast("/")
        val url = "https://$domain/series/$id"
        val doc = webClient.httpGet(url).parseHtml()

        // Parse Next.js hydration data
        val scriptContent = doc.select("script").find { it.html().contains("self.__next_f.push") && it.html().contains("mangaData") }?.html()
            ?: throw ParseException("Failed to find manga data", url)

        // Extract the JSON string. It's usually inside a push call.
        // The user example: self.__next_f.push([1,"5:[[\"$\",\"script\",null,{\"type\":\"application/ld+json\",\"dangerouslySetInnerHTML\":{\"__html\":\"$15\"}}],[\"$\",\"$L16\",null,{\"mangaData\":{...
        // This is complex to regex perfectly.
        // However, we can search for `\"mangaData\":` and extract the object.
        
        val jsonStr = extractJson(scriptContent, url)
        val json = JSONObject(jsonStr)
        val mangaData = json.getJSONObject("mangaData")
        
        val title = mangaData.getString("name")
        val cover = mangaData.getString("cover")
        val description = mangaData.optString("story")
        val statusVal = mangaData.optInt("status")
        val state = when (statusVal) {
            0 -> MangaState.ONGOING
            1 -> MangaState.FINISHED
            else -> MangaState.ONGOING
        }
        
        val authors = mutableSetOf<String>()
        mangaData.optString("author").takeIf { it != "null" }?.let { authors.add(it) }
        mangaData.optString("artist").takeIf { it != "null" }?.let { authors.add(it) }
        
        val genres = mangaData.optJSONArray("genre")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val g = arr.getString(i)
                MangaTag(key = g, title = g, source = source)
            }.toSet()
        } ?: emptySet()

        val chaptersData = json.getJSONArray("chaptersData")
        val chapters = (0 until chaptersData.length()).map { i ->
            val ch = chaptersData.getJSONObject(i)
            val chId = ch.getInt("id")
            val chNum = ch.optDouble("chapter", 0.0).toFloat()
            val chDate = ch.getString("postTime")
            val chTitle = ch.optString("title")
            
            MangaChapter(
                id = generateUid(chId.toLong()),
                title = if (chTitle.isNotEmpty()) chTitle else null,
                number = chNum,
                volume = 0,
                url = "/series/$id/$chNum", // URL is usually /series/ID/CHAPTER_NUM based on user link: https://waveteamy.com/series/1048778676/374
                uploadDate = parseDate(chDate),
                source = source,
                scanlator = null,
                branch = null
            )
        }

        return manga.copy(
            title = title,
            description = description,
            coverUrl = resolveCover(cover),
            state = state,
            authors = authors,
            tags = genres,
            chapters = chapters,
        )
    }

    private fun extractJson(script: String, url: String): String {
        // Quick and dirty extraction of the object containing mangaData
        // We look for `{"mangaData":` and try to find the matching closing brace.
        val startIndex = script.indexOf("{\"mangaData\":")
        if (startIndex == -1) throw ParseException("mangaData not found", url)
        
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
                        return script.substring(startIndex, i + 1).replace("\\\"", "\"").replace("\\\\", "\\")
                    }
                }
            }
        }
        throw ParseException("Failed to extract JSON", url)
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "https://$domain${chapter.url}"
        
        // Use evaluateJs to get the images because they are blobs
        val script = """
            (async () => {
                // Wait for images to load
                const sleep = ms => new Promise(r => setTimeout(r, ms));
                
                // Wait for at least one image to appear
                let attempts = 0;
                while (document.querySelectorAll('div.w-full.flex.justify-center img').length === 0 && attempts < 20) {
                    await sleep(500);
                    attempts++;
                }
                
                const images = Array.from(document.querySelectorAll('div.w-full.flex.justify-center img'));
                if (images.length === 0) return [];
                
                const results = [];
                for (const img of images) {
                    try {
                        // If it's a blob URL, fetch it and convert to base64
                        if (img.src.startsWith('blob:')) {
                            const response = await fetch(img.src);
                            const blob = await response.blob();
                            const reader = new FileReader();
                            await new Promise((resolve, reject) => {
                                reader.onloadend = () => resolve(reader.result);
                                reader.onerror = reject;
                                reader.readAsDataURL(blob);
                            });
                            results.push(reader.result);
                        } else {
                            results.push(img.src);
                        }
                    } catch (e) {
                        console.error(e);
                    }
                }
                return results;
            })();
        """.trimIndent()

        val result = context.evaluateJs(url, script, 30000L) as? List<*> 
            ?: throw ParseException("Failed to fetch pages", url)

        return result.filterIsInstance<String>().mapIndexed { i, imgData ->
            MangaPage(
                id = generateUid("${chapter.id}-$i"),
                url = imgData, // This will be a data URI or http URL
                preview = null,
                source = source
            )
        }
    }
}
