package org.koitharu.kotatsu.parsers.site.all

import androidx.collection.ArraySet
import androidx.collection.SparseArrayCompat
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

private const val CHAPTERS_LIMIT = 99999

@MangaSourceParser("COMICK_FUN", "ComicK")
internal class ComickFunParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.COMICK_FUN, 20) {

	override val configKeyDomain = ConfigKey.Domain("comick.live", "comick.art")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.RATING,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearRangeSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.OTHER,
		),
		availableDemographics = EnumSet.of(
			Demographic.SHOUNEN,
			Demographic.SHOUJO,
			Demographic.SEINEN,
			Demographic.JOSEI,
			Demographic.NONE,
		),
	)

	private var nextCursor: String? = null

	private fun isFilterEmpty(filter: MangaListFilter): Boolean {
		return filter.query.isNullOrEmpty() &&
			filter.tags.isEmpty() &&
			filter.tagsExclude.isEmpty() &&
			filter.demographics.isEmpty() &&
			filter.types.isEmpty() &&
			filter.states.isEmpty() &&
			filter.yearFrom == YEAR_UNKNOWN &&
			filter.yearTo == YEAR_UNKNOWN
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Handle special popular manga endpoint when sorting by popularity with no filters
		if (order == SortOrder.POPULARITY && isFilterEmpty(filter)) {
			return getPopularMangaList(page)
		}

		// Handle latest updates endpoint when sorting by updated with no filters
		if (order == SortOrder.UPDATED && isFilterEmpty(filter)) {
			return getLatestMangaList(page)
		}

		// Use search API for filtered results or specific sorts
		if (page == 1) {
			nextCursor = null
		}

		val url = urlBuilder()
			.scheme("https")
			.host(domain)
			.addPathSegment("api")
			.addPathSegment("search")
			.apply {
				addQueryParameter("order_by", when (order) {
					SortOrder.NEWEST -> "created_at"
					SortOrder.POPULARITY -> "view"
					SortOrder.RATING -> "rating"
					SortOrder.UPDATED -> "uploaded"
					else -> "uploaded"
				})
				addQueryParameter("order_direction", "desc")

				filter.tags.forEach {
					addQueryParameter("genres", it.key)
				}
				filter.tagsExclude.forEach {
					addQueryParameter("excludes", it.key)
				}

				filter.demographics.forEach {
					addQueryParameter("demographic", when (it) {
						Demographic.SHOUNEN -> "shounen"
						Demographic.SHOUJO -> "shoujo"
						Demographic.SEINEN -> "seinen"
						Demographic.JOSEI -> "josei"
						else -> ""
					})
				}

				filter.types.forEach {
					addQueryParameter("country", when (it) {
						ContentType.MANGA -> "jp"
						ContentType.MANHWA -> "kr"
						ContentType.MANHUA -> "cn"
						ContentType.OTHER -> "others"
						else -> ""
					})
				}

				filter.states.oneOrThrowIfMany()?.let {
					addQueryParameter("status", when (it) {
						MangaState.ONGOING -> "1"
						MangaState.FINISHED -> "2"
						MangaState.ABANDONED -> "3"
						MangaState.PAUSED -> "4"
						else -> ""
					})
				}

				if (filter.yearFrom != YEAR_UNKNOWN) {
					addQueryParameter("from", filter.yearFrom.toString())
				}
				if (filter.yearTo != YEAR_UNKNOWN) {
					addQueryParameter("to", filter.yearTo.toString())
				}

				addQueryParameter("showAll", "false")
				addQueryParameter("exclude_mylist", "false")

				filter.query?.let { query ->
					if (query.trim().length < 3) {
						throw IllegalArgumentException("Query must be at least 3 characters")
					}
					addQueryParameter("q", query.trim())
				}

				addQueryParameter("type", "comic")
				if (page > 1) {
					nextCursor?.let { cursor ->
						addQueryParameter("cursor", cursor)
					}
				}
			}

		val response = try {
			webClient.httpGet(url.build()).parseJson()
		} catch (e: Exception) {
			throw IllegalArgumentException("ComicK API error: ${e.message}")
		}

		nextCursor = response.optString("cursor").takeIf { it.isNotEmpty() }
		val data = response.getJSONArray("data")

		return parseMangaList(data)
	}

	private suspend fun getPopularMangaList(page: Int): List<Manga> {
		val url = urlBuilder()
			.scheme("https")
			.host(domain)
			.addPathSegment("api")
			.addPathSegment("comics")
			.addPathSegment("top")
			.apply {
				val days = when (page) {
					1, 4 -> 7
					2, 5 -> 30
					3, 6 -> 90
					else -> 7
				}
				val type = when (page) {
					1, 2, 3 -> "follow"
					4, 5, 6 -> "most_follow_new"
					else -> "follow"
				}
				addQueryParameter("days", days.toString())
				addQueryParameter("type", type)
			}

		val response = webClient.httpGet(url.build()).parseJson()
		val data = response.getJSONArray("data")
		return parseMangaList(data)
	}

	private suspend fun getLatestMangaList(page: Int): List<Manga> {
		val url = urlBuilder()
			.scheme("https")
			.host(domain)
			.addPathSegment("api")
			.addPathSegment("chapters")
			.addPathSegment("latest")
			.apply {
				addQueryParameter("order", "new")
				addQueryParameter("page", page.toString())
			}

		val response = webClient.httpGet(url.build()).parseJson()
		val data = response.getJSONArray("data")
		return parseMangaList(data)
	}

	private suspend fun parseMangaList(data: JSONArray): List<Manga> {
		val tagsMap = tagsArray.get()
		return data.mapJSON { jo ->
			val slug = jo.getString("slug")
			Manga(
				id = generateUid(slug),
				title = jo.getString("title"),
				altTitles = emptySet(),
				url = slug,
				publicUrl = "https://$domain/comic/$slug",
				rating = jo.getDoubleOrDefault("rating", -1.0).toFloat().let { if (it < 0) RATING_UNKNOWN else it / 10f },
				contentRating = when {
					jo.getBooleanOrDefault("hentai", false) -> ContentRating.ADULT
					jo.getBooleanOrDefault("suggestive", false) -> ContentRating.SUGGESTIVE
					else -> ContentRating.SAFE
				},
				coverUrl = jo.getStringOrNull("cover_url"),
				largeCoverUrl = null,
				description = jo.getStringOrNull("desc"),
				tags = jo.selectGenres(tagsMap),
				state = when (jo.getIntOrDefault("status", 0)) {
					1 -> MangaState.ONGOING
					2 -> MangaState.FINISHED
					3 -> MangaState.ABANDONED
					4 -> MangaState.PAUSED
					else -> null
				},
				authors = jo.optJSONArray("authors")?.mapJSONNotNullToSet { it.optString("name") }
					?: emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = "https://$domain/comic/${manga.url}"
		val response = webClient.httpGet(url)
		val html = response.parseHtml()

		// Extract comic data from HTML script tag
		val comicDataElement = html.selectFirst("#comic-data")
			?: throw IllegalArgumentException("Comic data not found")

		val comicDataJson = comicDataElement.data()
		val jo = JSONObject(comicDataJson)

		val alt = jo.optJSONArray("titles")?.asTypedList<JSONObject>()?.mapNotNullToSet {
			it.getStringOrNull("title")
		} ?: emptySet()

		val authors = jo.optJSONArray("authors")?.mapJSONNotNullToSet {
			it.getStringOrNull("name")
		} ?: emptySet()

		val artists = jo.optJSONArray("artists")?.mapJSONNotNullToSet {
			it.getStringOrNull("name")
		} ?: emptySet()

		return manga.copy(
			title = jo.getString("title"),
			altTitles = alt,
			contentRating = when (jo.getStringOrNull("content_rating")) {
				"suggestive" -> ContentRating.SUGGESTIVE
				"erotica" -> ContentRating.ADULT
				else -> ContentRating.SAFE
			},
			description = buildString {
				jo.getStringOrNull("desc")?.let { desc ->
					// Parse HTML description
					append(org.jsoup.Jsoup.parseBodyFragment(desc).wholeText())
				}

				if (alt.isNotEmpty()) {
					append("\n\n Alternative Titles: \n")
					alt.forEach {
						append("- ", it.trim(), "\n")
					}
				}
			}.trim(),
			tags = buildSet {
				// Add content type based on country
				when (jo.getStringOrNull("country")) {
					"jp" -> add(MangaTag("manga", "Manga", source))
					"cn" -> add(MangaTag("manhua", "Manhua", source))
					"ko" -> add(MangaTag("manhwa", "Manhwa", source))
				}
				// Add genres
				jo.optJSONArray("genres")?.let { genreArray ->
					for (i in 0 until genreArray.length()) {
						val genre = genreArray.getJSONObject(i)
						val genreInfo = genre.getJSONObject("genres")
						add(MangaTag(
							key = genreInfo.getString("slug"),
							title = genreInfo.getString("name"),
							source = source
						))
					}
				}
			},
			authors = authors + artists,
			chapters = getChapters(manga.url),
		)
	}

	private suspend fun getChapters(mangaSlug: String): List<MangaChapter> {
		val url = "https://$domain/api/comics/$mangaSlug/chapter-list"
		var response = webClient.httpGet(url).parseJson()
		val chapters = response.getJSONArray("data").asTypedList<JSONObject>().toMutableList()

		// Handle pagination if needed
		var page = 2
		var hasNextPage = response.optBoolean("hasNextPage", false)
		while (hasNextPage) {
			response = webClient.httpGet("$url?page=$page").parseJson()
			chapters.addAll(response.getJSONArray("data").asTypedList())
			hasNextPage = response.optBoolean("hasNextPage", false)
			page++
		}

		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ENGLISH)
		return chapters.mapChapters { _, jo ->
			val vol = jo.optString("vol").takeIf { it.isNotBlank() }
			val chap = jo.getString("chap")
			val title = jo.optString("title").takeIf { it.isNotBlank() }
			val groups = jo.optJSONArray("groups")?.asTypedList<String>()?.joinToString()
				?: ""

			MangaChapter(
				id = generateUid(jo.getString("hid")),
				title = buildString {
					if (!vol.isNullOrBlank()) {
						append("Vol. ", vol, " ")
					}
					append("Ch. ", chap)
					if (!title.isNullOrBlank()) {
						append(": ", title)
					}
				},
				number = chap.toFloatOrNull() ?: 0f,
				volume = vol?.toIntOrNull() ?: 0,
				url = "/comic/$mangaSlug/${jo.getString("hid")}-chapter-$chap-${jo.getString("lang")}",
				scanlator = groups.takeIf { it.isNotBlank() },
				uploadDate = dateFormat.parseSafe(jo.getString("created_at")),
				branch = null,
				source = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = "https://$domain${chapter.url}"
		val response = webClient.httpGet(fullUrl)
		val html = response.parseHtml()

		// Extract page data from HTML script tag
		val svDataElement = html.selectFirst("#sv-data")
			?: throw IllegalArgumentException("Page data not found")

		val pageDataJson = svDataElement.data()
		val jo = JSONObject(pageDataJson)

		val chapterData = jo.getJSONObject("chapter")
		val images = chapterData.getJSONArray("images")

		return images.mapIndexedNotNull { index, imageObj ->
			val imageData = imageObj as JSONObject
			val imageUrl = imageData.getString("url")
			MangaPage(
				id = generateUid("$imageUrl-$index"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val slug = link.pathSegments.lastOrNull() ?: return null
		return resolver.resolveManga(this, url = slug, id = generateUid(slug))
	}

	private val tagsArray = suspendLazy(initializer = ::loadTags)

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val sparseArray = tagsArray.get()
		val set = ArraySet<MangaTag>(sparseArray.size())
		for (i in 0 until sparseArray.size()) {
			set.add(sparseArray.valueAt(i))
		}
		return set
	}

	private suspend fun loadTags(): SparseArrayCompat<MangaTag> {
		val response = webClient.httpGet("https://$domain/api/metadata").parseJson()
		val genres = response.getJSONArray("genres")
		val tags = SparseArrayCompat<MangaTag>(genres.length())

		for (i in 0 until genres.length()) {
			val jo = genres.getJSONObject(i)
			tags.append(
				jo.getInt("id"),
				MangaTag(
					title = jo.getString("name").toTitleCase(Locale.ENGLISH),
					key = jo.getString("slug"),
					source = source,
				),
			)
		}
		return tags
	}

	private fun JSONObject.selectGenres(tags: SparseArrayCompat<MangaTag>): Set<MangaTag> {
		val array = optJSONArray("genres") ?: return emptySet()
		val res = ArraySet<MangaTag>(array.length())
		for (i in 0 until array.length()) {
			val id = array.getInt(i)
			val tag = tags[id] ?: continue
			res.add(tag)
		}
		return res
	}

	private fun JSONArray.joinToString(separator: String): String {
		return (0 until length()).joinToString(separator) { i -> getString(i) }
	}
}
