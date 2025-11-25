package org.koitharu.kotatsu.parsers.site.es

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.Base64
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAONI", "MangaOni", "es")
internal class MangaOni(context: MangaLoaderContext) :
	PagedMangaParser(context, source = MangaParserSource.MANGAONI, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("manga-oni.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)


	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = getAvailableTags(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.ONE_SHOT,
				ContentType.OTHER,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return if (!filter.query.isNullOrEmpty()) {
			// Search results
			val url = buildSearchUrl(page, filter.query!!)
			val doc = webClient.httpGet(url).parseHtml()
			parseSearchResults(doc)
		} else {
			// Directory results with NSFW detection
			getDirectoryWithNsfwDetection(page, order, filter)
		}
	}

	private suspend fun getDirectoryWithNsfwDetection(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Get all manga (including NSFW)
		val allUrl = buildDirectoryUrl(page, order, filter, includeNsfw = true)
		val allDoc = webClient.httpGet(allUrl).parseHtml()
		val allManga = parseDirectoryResults(allDoc)

		// Get safe manga only (no NSFW)
		val safeUrl = buildDirectoryUrl(page, order, filter, includeNsfw = false)
		val safeDoc = webClient.httpGet(safeUrl).parseHtml()
		val safeManga = parseDirectoryResults(safeDoc)
		val safeMangaUrls = safeManga.map { it.url }.toSet()

		// Mark NSFW content by comparing results
		return allManga.map { manga ->
			manga.copy(
				contentRating = if (safeMangaUrls.contains(manga.url)) {
					ContentRating.SAFE
				} else {
					ContentRating.ADULT
				}
			)
		}
	}

	private fun buildSearchUrl(page: Int, query: String): String {
		return "https://$domain/buscar".toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			.addQueryParameter("p", page.toString())
			.build()
			.toString()
	}

	private fun buildDirectoryUrl(page: Int, order: SortOrder, filter: MangaListFilter, includeNsfw: Boolean = true): String {
		return "https://$domain/directorio".toHttpUrl().newBuilder().apply {
			addQueryParameter("genero", getGenreParam(filter))
			addQueryParameter("estado", getStateParam(filter))
			addQueryParameter("filtro", getSortParam(order))
			addQueryParameter("tipo", getTypeParam(filter))
			addQueryParameter("adulto", if (includeNsfw) "false" else "0")
			addQueryParameter("orden", "desc")
			addQueryParameter("p", page.toString())
		}.build().toString()
	}

    private fun getGenreParam(filter: MangaListFilter): String {
        val tag = filter.tags.firstOrNull() ?: return "false"
        // If key is already numeric, use it
        if (tag.key.all { it.isDigit() }) {
            return tag.key
        }
        // Otherwise try to resolve by title (case-insensitive)
        val byTitle = getAvailableTags().firstOrNull {
            it.title.equals(tag.key, ignoreCase = true) || it.title.equals(tag.title, ignoreCase = true)
        }
        return byTitle?.key ?: "false"
    }

	private fun getStateParam(filter: MangaListFilter): String {
		return when (filter.states.firstOrNull()) {
			MangaState.ONGOING -> "1"
			MangaState.FINISHED -> "0"
			else -> "false"
		}
	}

	private fun getSortParam(order: SortOrder): String {
		return when (order) {
			SortOrder.POPULARITY -> "visitas"
			SortOrder.UPDATED -> "id"
			SortOrder.ALPHABETICAL -> "nombre"
			else -> "id" // Default to recent updates
		}
	}

	private fun getTypeParam(filter: MangaListFilter): String {
		return when (filter.types.firstOrNull()) {
			ContentType.MANGA -> "0"
			ContentType.MANHWA -> "1"
			ContentType.MANHUA -> "3"
			ContentType.ONE_SHOT -> "2"
			ContentType.OTHER -> "4"  // Novelas
			else -> "false"
		}
	}

	private fun parseDirectoryResults(doc: Document): List<Manga> {
		return doc.select("#article-div a").mapNotNull { element ->
			val href = element.attr("href")
			Manga(
				id = generateUid(href),
				title = element.select("div:eq(1)").text().trim(),
				altTitles = emptySet(),
				url = href.toRelativeUrl(domain),
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE, // Will be properly set in getDetails()
				coverUrl = element.select("img").attr("data-src"),
				largeCoverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				description = null,
				chapters = null,
				source = source,
			)
		}
	}

	private fun parseSearchResults(doc: Document): List<Manga> {
		return doc.select("div._2NNxg").mapNotNull { element ->
			val linkElement = element.selectFirst("a") ?: return@mapNotNull null
			val href = linkElement.attr("href")

			Manga(
				id = generateUid(href),
				title = linkElement.text().trim(),
				altTitles = emptySet(),
				url = href.toRelativeUrl(domain),
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE, // Will be properly set in getDetails()
                coverUrl = element.select("img").attr("src"),
				largeCoverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				description = null,
				chapters = null,
				source = source,
			)
		}
	}


	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val description = doc.select("div#sinopsis").lastOrNull()?.ownText()
		val author = doc.select("div#info-i").text().let {
			if (it.contains("Autor", true)) {
				it.substringAfter("Autor:").substringBefore("Fecha:").trim()
			} else {
				null
			}
		}
		val genres = doc.select("div#categ a").mapNotNull { element ->
			MangaTag(
				key = element.attr("href").substringAfterLast("="),
				title = element.text(),
				source = source,
			)
		}.toSet()

		val state = when (doc.select("strong:contains(Estado) + span").firstOrNull()?.text()) {
			"En desarrollo" -> MangaState.ONGOING
			"Finalizado" -> MangaState.FINISHED
			else -> null
		}

		val chapters = parseChapterList(doc)

		return manga.copy(
			title = doc.select("h1").firstOrNull()?.text() ?: manga.title,
			description = description,
			coverUrl = doc.select("img[src*=cover]").attr("abs:src").ifEmpty { manga.coverUrl },
			authors = setOfNotNull(author),
			tags = genres,
			state = state,
			chapters = chapters,
			// Keep existing contentRating from list page (determined by dual search)
		)
	}

	private fun parseChapterList(doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

		return doc.select("div#c_list a").mapIndexed { index, element ->
			val href = element.attr("href")
			MangaChapter(
				id = generateUid(href),
				title = element.text().trim(),
				number = element.select("span").attr("data-num").toFloatOrNull() ?: (index + 1).toFloat(),
				volume = 0,
				url = href.toRelativeUrl(domain),
				scanlator = null,
				uploadDate = dateFormat.parseSafe(element.select("span").attr("datetime")),
				branch = null,
				source = source,
			)
		}.asReversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		val scriptData = doc.select("script:containsData(unicap)").firstOrNull()
			?.data()?.substringAfter("'")?.substringBefore("'")
			?: throw Exception("unicap not found")

		val drop = scriptData.length % 4
		val cleanedData = scriptData.dropLast(drop)

		// Decode base64
		val decodedBytes = java.util.Base64.getDecoder().decode(cleanedData)
		val decoded = String(decodedBytes, Charsets.UTF_8)

		val path = decoded.substringBefore("||")
		val fileList = decoded.substringAfter("[").substringBefore("]")
			.split(",")
			.map { it.removeSurrounding("\"") }

		return fileList.mapIndexed { index, fileName ->
			MangaPage(
				id = generateUid("$path$fileName"),
				url = "$path$fileName",
				preview = null,
				source = source,
			)
		}
	}

	private fun getAvailableTags(): Set<MangaTag> {
		return setOf(
			MangaTag("1", "Comedia", source),
			MangaTag("2", "Drama", source),
			MangaTag("3", "Acción", source),
			MangaTag("4", "Escolar", source),
			MangaTag("5", "Romance", source),
			MangaTag("6", "Ecchi", source),
			MangaTag("7", "Aventura", source),
			MangaTag("8", "Shōnen", source),
			MangaTag("9", "Shōjo", source),
			MangaTag("10", "Deportes", source),
			MangaTag("11", "Psicológico", source),
			MangaTag("12", "Fantasía", source),
			MangaTag("13", "Mecha", source),
			MangaTag("14", "Gore", source),
			MangaTag("15", "Yaoi", source),
			MangaTag("16", "Yuri", source),
			MangaTag("17", "Misterio", source),
			MangaTag("18", "Sobrenatural", source),
			MangaTag("19", "Seinen", source),
			MangaTag("20", "Ficción", source),
			MangaTag("21", "Harem", source),
			MangaTag("25", "Webtoon", source),
			MangaTag("27", "Histórico", source),
			MangaTag("30", "Musical", source),
			MangaTag("31", "Ciencia ficción", source),
			MangaTag("32", "Shōjo-ai", source),
			MangaTag("33", "Josei", source),
			MangaTag("34", "Magia", source),
			MangaTag("35", "Artes Marciales", source),
			MangaTag("36", "Horror", source),
			MangaTag("37", "Demonios", source),
			MangaTag("38", "Supervivencia", source),
			MangaTag("39", "Recuentos de la vida", source),
			MangaTag("40", "Shōnen ai", source),
			MangaTag("41", "Militar", source),
			MangaTag("42", "Eroge", source),
			MangaTag("43", "Isekai", source),
		)
	}
}
