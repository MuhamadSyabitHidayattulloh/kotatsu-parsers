package org.koitharu.kotatsu.parsers.site.madara.all

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MANHUARM", "Manhuarm", "")
internal class Manhuarm(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHUARM, "manhuarmmtl.com", 20) {
	override val datePattern = "MMMM d, yyyy"
	override val sourceLocale: Locale = Locale.ENGLISH
	override val withoutAjax = true

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// Get standard pages from parent class
		val pages = super.getPages(chapter)
		
		// For MTL sources, we need to add OCR URL to each page
		// The OCR data is stored in /wp-content/uploads/ocr-data/{page-id}.json
		return pages.map { page ->
			// Extract page ID from the image URL
			// Example: https://manhuarmmtl.com/wp-content/uploads/WP-manga/data/manga_xyz/chapter-1/200165.webp
			// We need to get "200165" as the page ID
			val ocrUrl = try {
				val pageId = page.url.substringAfterLast('/').substringBeforeLast('.')
					.replace(Regex("[^0-9]"), "") // Remove non-numeric characters
				if (pageId.isNotEmpty()) {
					"https://${domain}/wp-content/uploads/ocr-data/${pageId}.json"
				} else {
					null
				}
			} catch (e: Exception) {
				null
			}
			
			MangaPage(
				id = page.id,
				url = page.url,
				preview = page.preview,
				source = page.source,
				ocrUrl = ocrUrl,
			)
		}
	}
}
