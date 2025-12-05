package org.koitharu.kotatsu.parsers.site.madara.all

import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaPageText
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MANHUARM", "Manhuarm", "")
internal class Manhuarm(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHUARM, "manhuarmmtl.com") {
	override val sourceLocale: Locale = Locale.ENGLISH

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val pages = super.getPages(chapter)
		val chapterId = Regex("""\d+""").findAll(pages.firstOrNull()?.url.orEmpty()).lastOrNull()?.value ?: return pages
		
		return try {
			val jsonArray = webClient.httpGet("https://$domain/wp-content/uploads/ocr-data/$chapterId.json").parseJsonArray()
			pages.mapIndexed { index, page ->
				if (index >= jsonArray.length()) return@mapIndexed page
				val texts = jsonArray.getJSONObject(index).optJSONArray("texts")?.let { arr ->
					(0 until arr.length()).mapNotNull { i ->
						val obj = arr.getJSONObject(i)
						val text = obj.getString("text").trim()
						if (text.isEmpty()) return@mapNotNull null
						val box = obj.getJSONArray("box")
						MangaPageText(
							Rect(box.getInt(0), box.getInt(1), box.getInt(0) + box.getInt(2), box.getInt(1) + box.getInt(3)),
							text
						)
					}.ifEmpty { null }
				}
				page.copy(texts = texts)
			}
		} catch (e: Exception) {
			pages
		}
	}
}
