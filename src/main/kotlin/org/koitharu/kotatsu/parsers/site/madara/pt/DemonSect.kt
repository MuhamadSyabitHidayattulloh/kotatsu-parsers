package org.koitharu.kotatsu.parsers.site.madara.pt

import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DEMONSECT", "DemonSect", "pt")
internal class DemonSect(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DEMONSECT, pageSize = 20) {

	override val configKeyDomain = org.koitharu.kotatsu.parsers.config.ConfigKey.Domain("seitacelestial.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = getTags(),
			availableStates = emptySet(),
			availableContentRating = emptySet(),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/projetos/".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())

			when (order) {
				SortOrder.UPDATED -> addQueryParameter("orderby", "meta_value_num")
				SortOrder.POPULARITY -> addQueryParameter("orderby", "meta_value_num")
				SortOrder.NEWEST -> addQueryParameter("orderby", "date")
				SortOrder.ALPHABETICAL -> addQueryParameter("orderby", "title")
				SortOrder.RATING -> addQueryParameter("orderby", "rating")
				else -> {}
			}

			if (!filter.query.isNullOrEmpty()) {
				// Use AJAX search
				return performSearch(filter.query)
			}

			filter.tags.forEach { tag ->
				addQueryParameter("genre[]", tag.key)
			}
		}.build()

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private suspend fun performSearch(query: String): List<Manga> {
		val searchUrl = "https://$domain/".toHttpUrl().newBuilder().apply {
			addQueryParameter("s", query)
			addQueryParameter("post_type", "wp-manga")
		}.build()

		val doc = webClient.httpGet(searchUrl).parseHtml()
		return parseSearchResults(doc)
	}

	private fun parseSearchResults(doc: Document): List<Manga> {
		return doc.select(".manga__item").mapNotNull { element ->
			val titleElement = element.selectFirst(".manga__content h2 a") ?: return@mapNotNull null
			val url = titleElement.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = titleElement.text().trim()

			val coverUrl = element.selectFirst(".manga__thumb img")?.src()

			// Extract description from manga-excerpt
			val description = element.selectFirst(".manga-excerpt")?.text()?.trim()

			// Extract genres
			val genres = element.select(".manga-genres .genre-item a").mapNotNullToSet { genreLink ->
				val genreName = genreLink.text().trim()
				if (genreName.isNotBlank()) {
					MangaTag(
						key = genreLink.attr("href").substringAfterLast("/").removeSuffix("/"),
						title = genreName,
						source = source,
					)
				} else null
			}

			// Extract chapter count
			val chapterInfo = element.selectFirst(".manga-info .total")?.text()?.trim()
			val chapterCount = chapterInfo?.let {
				Regex("(\\d+)\\s+Capítulos?").find(it)?.groupValues?.get(1)?.toIntOrNull()
			}

			Manga(
				id = generateUid(url),
				url = url,
				title = title,
				altTitles = emptySet(),
				publicUrl = titleElement.attrAsAbsoluteUrl("href"),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = coverUrl,
				tags = genres,
				state = null,
				authors = emptySet(),
				source = source,
				description = description,
			)
		}
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(".manga__item").mapNotNull { element ->
			val titleElement = element.selectFirst(".manga__content h2 a") ?: return@mapNotNull null
			val url = titleElement.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = titleElement.text().trim()

			val coverUrl = element.selectFirst(".manga__thumb img")?.src()

			Manga(
				id = generateUid(url),
				url = url,
				title = title,
				altTitles = emptySet(),
				publicUrl = titleElement.attrAsAbsoluteUrl("href"),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val description = doc.selectFirst(".summary__content p")?.text()?.trim()
			?: doc.selectFirst(".description-summary .summary__content")?.text()?.trim()

		val coverUrl = doc.selectFirst(".summary_image img")?.src()
			?: manga.coverUrl

		val status = when (doc.selectFirst(".summary-content .post-status .summary-content")?.text()?.lowercase()) {
			"ongoing", "em andamento" -> MangaState.ONGOING
			"completed", "completo" -> MangaState.FINISHED
			"hiatus", "em hiato" -> MangaState.PAUSED
			else -> null
		}

		val tags = doc.select(".genres-content a").mapNotNullToSet { element ->
			val tagName = element.text().trim()
			if (tagName.isNotBlank()) {
				MangaTag(
					key = element.attr("href").substringAfterLast("/").removeSuffix("/"),
					title = tagName,
					source = source,
				)
			} else null
		}

		val authors = doc.select(".author-content a").mapNotNullToSet { element ->
			element.text().trim().takeIf { it.isNotBlank() }
		}

		// Get chapters via AJAX
		val chapters = loadChapters(manga.url, doc)

		return manga.copy(
			description = description,
			coverUrl = coverUrl,
			tags = tags,
			authors = authors,
			state = status,
			chapters = chapters
		)
	}

	private suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
		val mangaId = extractMangaId(doc) ?: return emptyList()

		try {
			val chaptersUrl = "https://$domain/wp-admin/admin-ajax.php"
			val formData = mapOf(
				"action" to "manga_get_chapters",
				"manga" to mangaId.toString()
			)

			val extraHeaders = headersOf(
				"X-Requested-With", "XMLHttpRequest",
				"Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"
			)

			val response = webClient.httpPost(chaptersUrl.toHttpUrl(), formData, extraHeaders)
			val chaptersHtml = response.parseHtml()

			return parseChapters(chaptersHtml)
		} catch (e: Exception) {
			// Fallback to parsing chapters from page if AJAX fails
			return parseChaptersFromPage(doc)
		}
	}

	private fun extractMangaId(doc: Document): Int? {
		val scriptText = doc.select("script").joinToString("\n") { it.html() }
		return Regex("manga_id[\"']*\\s*:\\s*[\"']*?(\\d+)").find(scriptText)?.groupValues?.get(1)?.toIntOrNull()
	}

	private fun parseChapters(doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
		return doc.select("li.wp-manga-chapter").mapNotNull { element ->
			val link = element.selectFirst("a") ?: return@mapNotNull null
			val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = link.text().trim()

			val dateText = element.selectFirst(".chapter-release-date")?.text()?.trim()
			val uploadDate = dateText?.let {
			try {
				dateFormat.parse(it)?.time ?: 0L
			} catch (e: Exception) {
				0L
			}
		} ?: 0L

			val chapterNumber = Regex("cap[ítulo]*[\\s-]*(\\d+(?:\\.\\d+)?)").find(title.lowercase())
				?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

			MangaChapter(
				id = generateUid(url),
				title = title,
				url = url,
				number = chapterNumber,
				volume = 0,
				uploadDate = uploadDate,
				source = source,
				scanlator = null,
				branch = null,
			)
		}.reversed() // Most recent first
	}

	private fun parseChaptersFromPage(doc: Document): List<MangaChapter> {
		return doc.select(".wp-manga-chapter").mapNotNull { element ->
			val link = element.selectFirst("a") ?: return@mapNotNull null
			val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = link.text().trim()

			val chapterNumber = Regex("cap[ítulo]*[\\s-]*(\\d+(?:\\.\\d+)?)").find(title.lowercase())
				?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

			MangaChapter(
				id = generateUid(url),
				title = title,
				url = url,
				number = chapterNumber,
				volume = 0,
				uploadDate = 0L,
				source = source,
				scanlator = null,
				branch = null,
			)
		}.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		return doc.select(".reading-content img, .page-break img, #readerarea img").mapIndexed { index, img ->
			val imageUrl = img.src() ?: img.attr("data-src") ?: img.attr("data-lazy-src")

			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}.filter { it.url.isNotEmpty() }
	}

	private suspend fun getTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/projetos/").parseHtml()
		return doc.select(".genres-content a, .wp-manga-genre a").mapNotNullToSet { element ->
			val title = element.text().trim()
			if (title.isNotBlank()) {
				MangaTag(
					key = element.attr("href").substringAfterLast("/").removeSuffix("/"),
					title = title,
					source = source,
				)
			} else null
		}
	}
}
