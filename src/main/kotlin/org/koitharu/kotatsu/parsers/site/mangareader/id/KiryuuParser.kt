package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class KiryuuParser(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KIRYUU, "kiryuu03.com", pageSize = 50, searchPageSize = 10) {

	override val listUrl = "/manga/"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	// Override to handle AJAX search
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			return performAjaxSearch(filter.query)
		}
		return super.getListPage(page, order, filter)
	}

	private suspend fun performAjaxSearch(query: String): List<Manga> {
		try {
			// First, get nonce from main page
			val mainPage = webClient.httpGet("https://$domain/").parseHtml()
			val pageHtml = mainPage.html()

			// Try multiple ways to extract nonce
			val nonce = Regex("nonce[\"']\\s*:\\s*[\"']([^\"']+)[\"']").find(pageHtml)?.groupValues?.get(1)
				?: Regex("nonce=([a-f0-9]+)").find(pageHtml)?.groupValues?.get(1)
				?: "eadaed75c9" // Fallback from intercepted request

			// Make AJAX search request - based on intercepted request, body is just the query
			val searchUrl = "https://$domain/wp-admin/admin-ajax.php?nonce=$nonce&action=search"

			val response = webClient.httpPost(searchUrl, query)

			val searchResults = response.parseHtml()

			// Parse AJAX response - match the exact structure from intercepted response
			return searchResults.select("a[href*='/manga/']").filter { a ->
				// Skip the "Show more" link
				!a.text().contains("Show more") && a.selectFirst("h3") != null
			}.mapNotNull { a ->
				val url = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
				if (!url.contains("/manga/")) return@mapNotNull null

				val title = a.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
				val img = a.selectFirst("img")
				val coverUrl = img?.src()?.takeIf { it.isNotBlank() }

				Manga(
					id = generateUid(url),
					url = url,
					title = title,
					altTitles = emptySet(),
					publicUrl = a.attrAsAbsoluteUrl("href"),
					rating = RATING_UNKNOWN,
					contentRating = if (isNsfwSource) ContentRating.ADULT else null,
					coverUrl = coverUrl,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
		} catch (e: Exception) {
			// Fallback to empty list if AJAX search fails
			return emptyList()
		}
	}

	// Override parsing for new Kiryuu structure with multiple fallback approaches
	override fun parseMangaList(docs: Document): List<Manga> {
		// Look for manga cards in the grid structure - try multiple patterns
		val mangaCards = docs.select("a[href*='/manga/']:not([href*='chapter']):not([href*='page='])")
			.filter { a ->
				val href = a.attr("href")
				// Make sure this is a manga detail link, not chapter or pagination
				href.matches(Regex(".*/manga/[^/]+/?$"))
			}

		val mangaList = mangaCards.mapNotNull { a ->
			val url = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null

			// Try different ways to find the title
			val title = a.selectFirst("h2, h3, h1, .title, [class*='title']")?.text()?.trim()
				?: a.attr("title")?.takeIf { it.isNotBlank() }
				?: a.attr("aria-label")?.takeIf { it.isNotBlank() }
				?: a.ownText().trim().takeIf { it.isNotEmpty() && it.length > 2 }
				?: a.text().trim().takeIf { it.isNotEmpty() && it.length > 2 }
				?: return@mapNotNull null

			// Find image - look in the element and its children
			val img = a.selectFirst("img")
				?: a.parent()?.selectFirst("img")
				?: a.nextElementSibling()?.selectFirst("img")
				?: a.previousElementSibling()?.selectFirst("img")

			val coverUrl = img?.let { image ->
				image.attr("src").takeIf { it.isNotBlank() }
					?: image.attr("data-src").takeIf { it.isNotBlank() }
					?: image.attr("data-lazy-src").takeIf { it.isNotBlank() }
			}

			// Try to find rating
			val rating = a.selectFirst("[class*='rating'], .score, [class*='score']")?.text()?.trim()
				?.let { text ->
					Regex("(\\d+(?:\\.\\d+)?)").find(text)?.value?.toFloatOrNull()
				} ?: RATING_UNKNOWN

			Manga(
				id = generateUid(url),
				url = url,
				title = title,
				altTitles = emptySet(),
				publicUrl = a.attrAsAbsoluteUrl("href"),
				rating = rating,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}

		return mangaList.distinctBy { it.url } // Remove duplicates
	}

	// Override chapter parsing for Kiryuu's structure
	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		// First, try to find chapters in the page using multiple approaches
		// Look for chapter links with the specific .XXXXXX pattern used by this site
		var chapters = docs.select("a[href*='chapter']").filter { element ->
			val href = element.attr("href")
			href.contains("/chapter-") && Regex("/chapter-\\d+\\.\\d+").containsMatchIn(href)
		}.mapNotNull { element ->
			val url = element.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			if (!url.contains("/chapter-")) return@mapNotNull null

			val chapterText = element.text().trim()
			val title = when {
				chapterText.isNotEmpty() && chapterText.contains("Chapter", ignoreCase = true) -> chapterText
				chapterText.isNotEmpty() && chapterText.matches(Regex("\\d+.*")) -> "Chapter $chapterText"
				else -> {
					// Extract chapter number from URL - handle the .XXXXXX suffix pattern
					val chapterNum = Regex("/chapter[.-](\\d+)(?:\\.\\d+)?").find(url)?.groupValues?.get(1)
						?: Regex("(?:chapter|ch)[.-](\\d+)").find(url)?.groupValues?.get(1)
						?: Regex("/(\\d+)/?$").find(url)?.groupValues?.get(1)
					chapterNum?.let { "Chapter $it" }
				}
			} ?: return@mapNotNull null

			// Extract chapter number for sorting
			val chapterNumber = Regex("(\\d+(?:\\.\\d+)?)").find(title)?.value?.toFloatOrNull() ?: 0f

			MangaChapter(
				id = generateUid(url),
				title = title,
				url = url,
				number = chapterNumber,
				volume = 0,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}.distinctBy { it.url }.sortedBy { it.number }

		// If no chapters found, try broader selectors and look for hidden/collapsed chapters
		if (chapters.isEmpty()) {
			// Try looking in potential chapter containers
			val chapterContainers = docs.select(".chapters, .chapter-list, .episode-list, [class*='chapter'], [class*='episode']")
			for (container in chapterContainers) {
				val containerChapters = container.select("a[href*='chapter']").filter { element ->
					val href = element.attr("href")
					href.contains("/chapter-")
				}
				if (containerChapters.isNotEmpty()) {
					chapters = containerChapters.mapNotNull { element ->
						val url = element.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
						val chapterText = element.text().trim()
						val chapterNum = Regex("/chapter[.-](\\d+)").find(url)?.groupValues?.get(1) ?: "1"
						val title = if (chapterText.contains("Chapter", ignoreCase = true)) chapterText else "Chapter $chapterNum"

						MangaChapter(
							id = generateUid(url),
							title = title,
							url = url,
							number = chapterNum.toFloatOrNull() ?: 1f,
							volume = 0,
							scanlator = null,
							uploadDate = 0L,
							branch = null,
							source = source,
						)
					}.distinctBy { it.url }.sortedBy { it.number }
					break
				}
			}
		}

		// If still no chapters found, try to find any links that might be chapter-related (e.g., read buttons, chapter navigation)
		if (chapters.isEmpty()) {
			val readLinks = docs.select("a").filter { element ->
				val text = element.text().lowercase()
				val href = element.attr("href")
				(text.contains("read") || text.contains("chapter") || text.contains("baca"))
					&& href.isNotEmpty() && !href.contains("/manga/") && !href.contains("search")
			}

			if (readLinks.isNotEmpty()) {
				chapters = readLinks.mapNotNull { element ->
					val url = element.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
					val text = element.text().trim()
					val title = if (text.contains("Chapter", ignoreCase = true)) text else "Chapter 1"

					MangaChapter(
						id = generateUid(url),
						title = title,
						url = url,
						number = 1f,
						volume = 0,
						scanlator = null,
						uploadDate = 0L,
						branch = null,
						source = source,
					)
				}.take(1) // Take only the first read link
			} else {
				// Last resort: create dummy chapter
				val pageUrl = manga.url
				val dummyChapter = MangaChapter(
					id = generateUid(pageUrl + "/read"),
					title = "Read",
					url = pageUrl + "/read",
					number = 1f,
					volume = 0,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
				chapters = listOf(dummyChapter)
			}
		}

		return parseInfo(docs, manga, chapters)
	}
}
