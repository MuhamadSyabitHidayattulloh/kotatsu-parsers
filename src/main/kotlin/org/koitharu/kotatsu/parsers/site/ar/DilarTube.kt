package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DILARTUBE", "Dilar Tube", "ar", ContentType.MANGA)
internal class DilarTube(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DILARTUBE, 24) {

    override val configKeyDomain = ConfigKey.Domain("v2.dilar.tube")

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
        )

    override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.RELEVANCE)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val response = webClient.httpGet("https://v2.dilar.tube/api/categories").parseJsonArray()
        val tags = mutableSetOf<MangaTag>()

        for (i in 0 until response.length()) {
            val group = response.getJSONObject(i)
            val groupId = group.getInt("id")
            val categories = group.getJSONArray("categories")

            // Group 3 is "Style" (Manhwa, etc) -> seriesType
            // Others -> categories
            val prefix = if (groupId == 3) "seriesType" else "categories"

            for (j in 0 until categories.length()) {
                val category = categories.getJSONObject(j)
                tags.add(
                    MangaTag(
                        key = "$prefix:${category.getInt("id")}",
                        title = category.getString("name"),
                        source = source,
                    )
                )
            }
        }
        return tags
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://v2.dilar.tube/api/search/filter"

        val seriesTypeInclude = mutableListOf<Int>()
        val seriesTypeExclude = mutableListOf<Int>()
        val categoriesInclude = mutableListOf<Int>()
        val categoriesExclude = mutableListOf<Int>()

        filter.tags.forEach { tag ->
            val parts = tag.key.split(":")
            if (parts.size == 2) {
                val type = parts[0]
                val id = parts[1].toIntOrNull() ?: return@forEach
                if (type == "seriesType") {
                    seriesTypeInclude.add(id)
                } else {
                    categoriesInclude.add(id)
                }
            }
        }

        filter.tagsExclude.forEach { tag ->
            val parts = tag.key.split(":")
            if (parts.size == 2) {
                val type = parts[0]
                val id = parts[1].toIntOrNull() ?: return@forEach
                if (type == "seriesType") {
                    seriesTypeExclude.add(id)
                } else {
                    categoriesExclude.add(id)
                }
            }
        }

        val jsonBody = JSONObject().apply {
            put("query", filter.query ?: "")
            put("seriesType", JSONObject().apply {
                put("include", JSONArray(seriesTypeInclude))
                put("exclude", JSONArray(seriesTypeExclude))
            })
            put("oneshot", false)
            put("categories", JSONObject().apply {
                put("include", JSONArray(categoriesInclude))
                put("exclude", JSONArray(categoriesExclude))
            })
            put("chapters", JSONObject().apply {
                put("min", "")
                put("max", "")
            })
            put("dates", JSONObject().apply {
                put("start", JSONObject.NULL)
                put("end", JSONObject.NULL)
            })
            put("page", page)
        }

        val response = webClient.httpPost(url, jsonBody).parseJson()
        val rows = response.getJSONArray("rows")

        return (0 until rows.length()).map { i ->
            val item = rows.getJSONObject(i)
            parseMangaFromJson(item)
        }
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val id = json.getInt("id")
        val title = json.getString("title")
        val cover = json.optString("cover").nullIfEmpty()
        val coverUrl = if (cover != null) "https://v2.dilar.tube/uploads/$cover" else null

        val rating = json.optString("rating", "0.0").toFloatOrNull() ?: 0f
        val normalizedRating = if (rating > 0) rating / 2f else RATING_UNKNOWN

        val statusStr = json.optString("story_status") ?: json.optString("status")
        val state = when (statusStr?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.ONGOING
            else -> null
        }

        return Manga(
            id = generateUid(id.toString()),
            url = "/api/series/$id",
            publicUrl = "https://dilar.tube/series/$id",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            rating = normalizedRating,
            tags = emptySet(),
            authors = emptySet(),
            state = state,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfterLast("/")
        val url = "https://v2.dilar.tube/api/series/$id"
        val json = webClient.httpGet(url).parseJson()

        val title = json.getString("title")
        val summary = json.optString("summary").nullIfEmpty()
        
        val cover = json.optString("cover").nullIfEmpty()
        val coverUrl = if (cover != null) "https://v2.dilar.tube/uploads/$cover" else manga.coverUrl

        val statusStr = json.optString("story_status")
        val state = when (statusStr?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.ONGOING
            else -> null
        }

        val authors = mutableSetOf<String>()
        json.optJSONObject("creator")?.let {
            authors.add(it.getString("nick"))
        }

        val tags = mutableSetOf<MangaTag>()
        val categories = json.optJSONArray("categories")
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                tags.add(MangaTag(
                    key = cat.getInt("id").toString(),
                    title = cat.getString("name"),
                    source = source
                ))
            }
        }

        return manga.copy(
            title = title,
            description = summary,
            coverUrl = coverUrl,
            state = state,
            authors = authors,
            tags = tags,
            chapters = getChapters(id),
        )
    }

    private suspend fun getChapters(seriesId: String): List<MangaChapter> {
        val url = "https://v2.dilar.tube/api/series/$seriesId/chapters"
        val response = webClient.httpGet(url).parseJson()
        val chaptersJson = response.getJSONArray("chapters")
        val chapters = mutableListOf<MangaChapter>()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

        for (i in 0 until chaptersJson.length()) {
            val item = chaptersJson.getJSONObject(i)
            val releases = item.getJSONArray("releases")
            if (releases.length() == 0) continue

            val release = releases.getJSONObject(0)
            val releaseId = release.getInt("id")
            
            val chapterNum = item.optString("chapter").toFloatOrNull() ?: 0f
            val volNum = item.optString("volume").toIntOrNull() ?: 0
            val title = item.optString("title").nullIfEmpty() ?: ""
            
            val dateStr = item.optString("created_at")
            val date = try {
                dateFormat.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }

            chapters.add(
                MangaChapter(
                    id = generateUid(releaseId.toString()),
                    title = title,
                    number = chapterNum,
                    volume = volNum,
                    url = "/api/chapters/$releaseId",
                    uploadDate = date,
                    source = source,
                    scanlator = null,
                    branch = null
                )
            )
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val id = chapter.url.substringAfterLast("/")
        val url = "https://v2.dilar.tube/api/chapters/$id"
        val json = webClient.httpGet(url).parseJson()
        val pagesJson = json.getJSONArray("pages")
        val storageKey = json.optString("storage_key").nullIfEmpty()

        return (0 until pagesJson.length()).map { i ->
            val page = pagesJson.getJSONObject(i)
            val imageUrl = page.getString("url")
            
            val fullUrl = if (imageUrl.startsWith("http")) {
                imageUrl
            } else {
                if (storageKey != null) {
                    "https://v2.dilar.tube/uploads/$storageKey/$imageUrl"
                } else {
                    "https://v2.dilar.tube/uploads/$imageUrl"
                }
            }

            MangaPage(
                id = generateUid("$id-$i"),
                url = fullUrl,
                preview = null,
                source = source
            )
        }
    }
}
