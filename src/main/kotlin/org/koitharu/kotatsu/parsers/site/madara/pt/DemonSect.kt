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

		// Parse actual chapters from the page
		val chapters = parseChaptersFromPage(doc)

		return manga.copy(
			description = description,
			coverUrl = coverUrl,
			tags = tags,
			authors = authors,
			state = status,
			chapters = chapters
		)
	}


	private fun extractChapterCount(doc: Document): Int {
		// Try to find any heading with chapter information
		val allHeadings = doc.select("h1, h2, h3, h4, h5, .heading, .title")

		for (heading in allHeadings) {
			val text = heading.text().trim()
			if (text.contains("chapter", ignoreCase = true) || text.contains("capítulo", ignoreCase = true)) {
				// Try multiple regex patterns
				val patterns = listOf(
					"Chapters?:\\s*(\\d+)",
					"Capítulos?:\\s*(\\d+)",
					"(\\d+)\\s*Chapters?",
					"(\\d+)\\s*Capítulos?",
					"Total:\\s*(\\d+)",
					"(\\d+)\\s*$" // Just a number at the end
				)

				for (pattern in patterns) {
					val match = Regex(pattern, RegexOption.IGNORE_CASE).find(text)
					if (match != null) {
						val count = match.groupValues[1].toIntOrNull()
						if (count != null && count > 0) {
							return count
						}
					}
				}
			}
		}

		// Fallback: look for any element containing just numbers that might be chapter count
		val possibleCounts = doc.select("*").mapNotNull { element ->
			val text = element.ownText().trim()
			if (text.matches(Regex("\\d{2,3}"))) { // 2-3 digit numbers (likely chapter counts)
				text.toIntOrNull()
			} else null
		}.filter { it > 10 && it < 1000 } // Reasonable chapter count range

		return possibleCounts.maxOrNull() ?: 0
	}

	private fun parseChaptersFromPage(doc: Document): List<MangaChapter> {
		// Parse the specific structure shown in the HTML
		val chapters = doc.select("li.wp-manga-chapter").mapNotNull { li ->
			val link = li.selectFirst("a") ?: return@mapNotNull null
			val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = link.text().trim()

			// Extract chapter number from title or URL
			val chapterNumber = Regex("Cap\\.?\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toFloatOrNull()
				?: Regex("/cap-(\\d+)/?$").find(url)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

			// Extract upload date from the chapter-release-date span
			val uploadDate = try {
				val dateElement = li.selectFirst(".chapter-release-date .timediff")
				val dateText = dateElement?.text()?.trim()?.removeSurrounding("", "")
				if (dateText != null) {
					// Parse Portuguese date format: "outubro 19, 2025"
					val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("pt", "BR"))
					dateFormat.parse(dateText)?.time ?: 0L
				} else 0L
			} catch (e: Exception) {
				0L
			}

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
		}.sortedBy { it.number }

		return chapters
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
