package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.RequestBody.Companion.toRequestBody
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
			val requestBody = query.toRequestBody(null)
			val searchUrl = "https://$domain/wp-admin/admin-ajax.php?nonce=$nonce&action=search"

			val response = webClient.httpPost(searchUrl, requestBody)

			val searchResults = response.parseHtml()

			// Parse AJAX response - match the exact structure from intercepted response
			return searchResults.select("a[class*=\"flex items-center\"]").mapNotNull { a ->
				val url = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
				if (!url.contains("/manga/")) return@mapNotNull null

				val title = a.selectFirst("h3[class*=\"text-lg\"]")?.text()?.trim() ?: return@mapNotNull null
				val img = a.selectFirst("img")
				val coverUrl = img?.src()

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
		// Approach 1: Look for any link containing /manga/
		val mangaLinks = docs.select("a[href*='/manga/']").mapNotNull { a ->
			val url = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			if (url.contains("/chapter-") || url.contains("page=")) return@mapNotNull null // Skip chapter and pagination links

			// Extract title from text content
			val title = a.ownText().trim() // Get direct text content of the <a> tag
				.takeIf { it.isNotEmpty() && it.length > 2 && !it.contains("Chapter") }
				?: a.selectFirst("h2, h3, h1")?.text()?.trim()  // Try heading tags
				?: a.text().trim().substringBefore("Chapter").trim() // Get text before "Chapter"
				.takeIf { it.isNotEmpty() && it.length > 2 }
				?: return@mapNotNull null

			// Look for image in this element or nearby
			val img = a.selectFirst("img")
				?: a.parent()?.selectFirst("img")
				?: a.nextElementSibling()?.selectFirst("img")

			val coverUrl = img?.src()

			// Try to find rating
			val rating = (a.selectFirst("span") ?: a.parent()?.selectFirst("span"))?.text()?.trim()
				?.let { text ->
					Regex("(\\d+\\.\\d+)").find(text)?.value?.toFloatOrNull()
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

		return mangaLinks.distinctBy { it.url } // Remove duplicates
	}

	// Override chapter parsing for Kiryuu's structure
	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		// First, try to find chapters in the page
		var chapters = docs.select("a").filter { element ->
			val href = element.attr("href")
			href.contains("/chapter-") || href.contains("/ch-")
		}.mapNotNull { element ->
			val url = element.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			if (!url.contains("/chapter")) return@mapNotNull null

			val chapterText = element.text().trim()
			val title = when {
				chapterText.isNotEmpty() && chapterText.contains("Chapter", ignoreCase = true) -> chapterText
				chapterText.isNotEmpty() && chapterText.matches(Regex("\\d+.*")) -> "Chapter $chapterText"
				else -> {
					// Extract chapter number from URL if no usable text
					val chapterNum = Regex("chapter[.-](\\d+(?:\\.\\d+)?)").find(url)?.groupValues?.get(1)
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

		// If no chapters found, try to create a dummy chapter from the current page
		if (chapters.isEmpty()) {
			val pageUrl = manga.url
			val dummyChapter = MangaChapter(
				id = generateUid(pageUrl + "/chapter-1"),
				title = "Chapter 1",
				url = pageUrl + "/chapter-1",
				number = 1f,
				volume = 0,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
			chapters = listOf(dummyChapter)
		}

		return parseInfo(docs, manga, chapters)
	}
}
