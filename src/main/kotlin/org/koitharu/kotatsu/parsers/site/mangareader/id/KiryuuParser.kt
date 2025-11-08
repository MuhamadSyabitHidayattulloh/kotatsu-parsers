package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class KiryuuParser(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KIRYUU, "kiryuu03.com", pageSize = 50, searchPageSize = 10) {

	override val listUrl = "/manga/"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	// Override parsing for new Kiryuu structure with multiple fallback approaches
	override fun parseMangaList(docs: Document): List<Manga> {
		// Approach 1: Look for any link containing /manga/
		val mangaLinks = docs.select("a[href*='/manga/']").mapNotNull { a ->
			val url = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			if (url.contains("/chapter-") || url.contains("page=")) return@mapNotNull null // Skip chapter and pagination links

			// Try to find title in various ways
			val title = a.selectFirst("h3, h2, h1")?.text()?.trim()
				?: a.text().trim().takeIf { it.length > 3 && it.length < 100 }
				?: a.attr("title")?.trim()
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
}
