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

	// Override parsing for new Kiryuu structure (no CSS classes)
	override fun parseMangaList(docs: Document): List<Manga> {
		return docs.select("div").filter { div ->
			// Find divs that contain manga links
			div.selectFirst("a[href*='/manga/']") != null &&
			div.selectFirst("h2") != null
		}.mapNotNull { item ->
			val a = item.selectFirst("a[href*='/manga/']") ?: return@mapNotNull null
			val url = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null

			val title = item.selectFirst("h2")?.text()?.trim() ?: return@mapNotNull null

			val img = item.selectFirst("img")
			val coverUrl = img?.src()

			// Rating is in a div element (7.00, etc.)
			val rating = item.select("div").find {
				it.text().matches(Regex("\\d+\\.\\d+"))
			}?.text()?.toFloatOrNull() ?: RATING_UNKNOWN

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
	}
}
