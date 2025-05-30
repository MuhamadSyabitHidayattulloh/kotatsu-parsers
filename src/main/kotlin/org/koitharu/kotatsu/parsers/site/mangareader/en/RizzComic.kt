package org.koitharu.kotatsu.parsers.site.mangareader.en

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.*

@MangaSourceParser("RIZZCOMIC", "RizzComic", "en")
internal class RizzComic(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.RIZZCOMIC, "rizzfables.com", pageSize = 50, searchPageSize = 20) {

	override val datePattern = "dd MMM yyyy"
	override val listUrl = "/series"
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isTagsExclusionSupported = false,
		)

	private val filterUrl = "/Index/filter_series"
	private val searchUrl = "/Index/live_search"
	private var randomPartCache = suspendLazy(initializer = ::getRandomPart)
	private val randomPartRegex = Regex("""^(r\d+-)""")
	private val slugRegex = Regex("""[^a-z0-9]+""")
	private val searchMangaSelector = ".utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx"
	private suspend fun getRandomPart(): String {
		val response = webClient.httpGet("https://$domain$listUrl").parseHtml()
		val url = response
			.selectFirst(searchMangaSelector)!!
			.select("a").attr("href")

		val slug = url
			.removeSuffix("/")
			.substringAfterLast("/")

		return randomPartRegex.find(slug)?.groupValues?.get(1) ?: ""
	}

	override suspend fun getFilterOptions() = super.getFilterOptions().copy(
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (page > 1) {
			return emptyList()
		}
		var url = "https://$domain$filterUrl"

		val payload = when {
			!filter.query.isNullOrEmpty() -> {
				url = "https://$domain$searchUrl"
				if (filter.query != "") {
					mapOf("search_value" to filter.query.trim())
				} else {
					null
				}
			}

			else -> {
				val state = filter.states.oneOrThrowIfMany()?.toPayloadValue() ?: "all"

				val genres = filter.tags.map { it.key }

				val form = ArrayMap<String, String>()
				form["StatusValue"] = state
				form["TypeValue"] = "all"
				form["OrderValue"] = order.toPayloadValue()

				genres.forEach { genre ->
					form["genres_checked[]"] = genre
				}
				form
			}
		}
		val response = if (payload != null) {
			webClient.httpPost(url, payload)
		} else {
			webClient.httpGet(url)
		}.parseJsonArray()
		return response.mapJSON { j ->
			val title = j.getString("title")
			val urlManga = "https://$domain$listUrl/${randomPartCache.get()}-" + title.trim().lowercase()
				.replace(slugRegex, "-")
				.replace("-s-", "s-")
				.replace("-ll-", "ll-")
			val author = j.getStringOrNull("author")

			val manga = Manga(
				id = j.getLong("id"),
				title = title,
				altTitles = emptySet(), //j.getString("description"), TODO check
				url = urlManga.toRelativeUrl(domain),
				publicUrl = urlManga,
				rating = j.getFloatOrDefault("rating", RATING_UNKNOWN) / 10f,
				contentRating = null,
				coverUrl = "https://$domain/assets/images/" + j.getString("image_url"),
				tags = setOf(),
				state = when (j.getString("status")) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"hiatus" -> MangaState.PAUSED
					else -> null
				},
				authors = setOfNotNull(author),
				source = source,
				description = j.getString("long_description"),
			)
			manga

		}

	}

	private fun SortOrder.toPayloadValue(): String = when (this) {
		SortOrder.ALPHABETICAL -> "title"
		SortOrder.POPULARITY -> "popular"
		SortOrder.UPDATED -> "update"
		SortOrder.NEWEST -> "latest"
		SortOrder.ALPHABETICAL_DESC -> "titlereverse"
		else -> "all"
	}

	private fun MangaState.toPayloadValue(): String = when (this) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		else -> "all"
	}

	override suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
		tagCache?.let { return@withLock it }
		val url = "https://$domain/series"
		val doc = webClient.httpGet(url).parseHtml()

		val genreElements = doc.select("input.genre-item")

		val genres = genreElements.mapNotNull { element ->
			val id = element.attr("value")
			val name = element.nextElementSibling()?.text()

			if (id.isNotEmpty() && name != null) {
				MangaTag(
					key = id,
					title = name.toTitleCase(sourceLocale),
					source = source,
				)
			} else {
				null
			}
		}
		genres.associateByTo(ArrayMap(genres.size)) { it.title }.also { tagCache = it }
	}
}
