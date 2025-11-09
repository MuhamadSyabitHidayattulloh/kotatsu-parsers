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
			availableTags = emptySet(), // Don't load tags to avoid issues
			availableStates = emptySet(),
			availableContentRating = emptySet(),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Handle search separately
		if (!filter.query.isNullOrEmpty()) {
			return performSearch(filter.query)
		}

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

		// Extract chapter range from navigation buttons and generate all chapters
		val chapters = generateChaptersFromNavigation(doc)

		return manga.copy(
			description = description,
			coverUrl = coverUrl,
			tags = tags,
			authors = authors,
			state = status,
			chapters = chapters
		)
	}


	private fun generateChaptersFromNavigation(doc: Document): List<MangaChapter> {
		// Find the navigation buttons to extract first and last chapter numbers
		val firstChapterBtn = doc.selectFirst("#btn-read-last, a:contains(Leia primeiro)")
		val lastChapterBtn = doc.selectFirst("#btn-read-first, a:contains(Leia por último)")

		if (firstChapterBtn == null || lastChapterBtn == null) {
			return emptyList()
		}

		val firstChapterUrl = firstChapterBtn.attr("href")
		val lastChapterUrl = lastChapterBtn.attr("href")

		// Extract chapter numbers from URLs
		val firstChapterNum = Regex("/cap-(\\d+)/?").find(firstChapterUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
		val lastChapterNum = Regex("/cap-(\\d+)/?").find(lastChapterUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0

		if (firstChapterNum < 0 || lastChapterNum < firstChapterNum) {
			return emptyList()
		}

		// Get the base URL from either chapter URL
		val baseUrl = firstChapterUrl.substringBeforeLast("/cap-")

		// Generate all chapters from first to last
		return (firstChapterNum..lastChapterNum).map { chapterNum ->
			val chapterUrl = "$baseUrl/cap-$chapterNum/"

			MangaChapter(
				id = generateUid(chapterUrl),
				title = "Cap. ${String.format("%02d", chapterNum)}", // Format as "Cap. 00", "Cap. 01", etc.
				url = chapterUrl,
				number = chapterNum.toFloat(),
				volume = 0,
				uploadDate = 0L,
				source = source,
				scanlator = null,
				branch = null,
			)
		}
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
