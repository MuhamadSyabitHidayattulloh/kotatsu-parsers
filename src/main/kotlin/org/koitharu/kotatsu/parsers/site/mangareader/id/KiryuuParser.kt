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

	// Override parsing for WordPress block theme
	override fun parseMangaList(docs: Document): List<Manga> {
		return docs.select(".wp-block-post-template a, .manga-grid a, .manga-card a").mapNotNull { a ->
			val url = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			if (!url.contains("/manga/")) return@mapNotNull null

			val title = a.selectFirst("h3, .title")?.text()?.trim()
				?: a.attr("title")?.trim()
				?: return@mapNotNull null

			val img = a.selectFirst("img")
			val coverUrl = img?.src()

			val rating = a.selectFirst(".rating, .score")?.text()?.trim()
				?.toFloatOrNull() ?: RATING_UNKNOWN

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
