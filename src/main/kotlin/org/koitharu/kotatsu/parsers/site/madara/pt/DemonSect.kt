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
		// Extract total chapter count from "Chapters: 202" text
		val totalChapters = extractChapterCount(doc)

		if (totalChapters > 0) {
			// Generate all chapters from 1 to totalChapters
			return (1..totalChapters).map { chapterNum ->
				val chapterUrl = "${mangaUrl.removeSuffix("/")}/capitulo-$chapterNum/"

				MangaChapter(
					id = generateUid(chapterUrl),
					title = "Capítulo $chapterNum",
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

		return emptyList()
	}

	private fun extractChapterCount(doc: Document): Int {
		// Try multiple selectors and patterns to find chapter count
		val selectors = listOf(
			".c-blog__heading h2",
			".c-blog__heading .h4",
			".wp-manga-chapter-count",
			".chapter-count",
			"h2:contains(Chapters)",
			"h2:contains(Capítulos)"
		)

		for (selector in selectors) {
			val element = doc.selectFirst(selector)
			if (element != null) {
				val text = element.text().trim()
				// Try multiple regex patterns
				val patterns = listOf(
					"Chapters?:\\s*(\\d+)",
					"Capítulos?:\\s*(\\d+)",
					"(\\d+)\\s+Chapters?",
					"(\\d+)\\s+Capítulos?"
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

		return 0
	}

	private fun parseChaptersFromPage(doc: Document): List<MangaChapter> {
		// Try multiple selectors for chapter lists
		val selectors = listOf(
			".wp-manga-chapter a",
			".chapter-item a",
			"li.wp-manga-chapter a",
			".listing-chapters_wrap a[href*='capitulo']",
			".listing-chapters_wrap a[href*='chapter']",
			"a[href*='/capitulo-']",
			"a[href*='/chapter-']",
			"a[href*='cap-']"
		)

		for (selector in selectors) {
			val chapters = doc.select(selector).mapNotNull { link ->
				val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
				if (!url.contains("cap") && !url.contains("chapter")) return@mapNotNull null

				val title = link.text().trim().ifEmpty {
					// Try to get title from parent or nearby elements
					link.parent()?.selectFirst(".chapter-title, .title")?.text()?.trim() ?: "Chapter"
				}

				val chapterNumber = Regex("cap[ítulo]*[\\s-]*(\\d+(?:\\.\\d+)?)").find(title.lowercase())
					?.groupValues?.get(1)?.toFloatOrNull()
					?: Regex("(\\d+(?:\\.\\d+)?)").find(url)?.value?.toFloatOrNull() ?: 0f

				// Try to extract upload date from nearby elements
				val uploadDate = try {
					val dateElement = link.parent()?.selectFirst(".chapter-release-date, .post-on, .chapter-date")
					val dateText = dateElement?.text()?.trim()
					if (dateText != null) {
						val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
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
			}.distinctBy { it.url }

			if (chapters.isNotEmpty()) {
				return chapters.sortedBy { it.number }
			}
		}

		return emptyList()
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
