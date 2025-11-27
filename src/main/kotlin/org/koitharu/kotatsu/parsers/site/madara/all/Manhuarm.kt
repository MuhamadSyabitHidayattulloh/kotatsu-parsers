package org.koitharu.kotatsu.parsers.site.madara.all

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.*

@MangaSourceParser("MANHUARM", "Manhuarm", "")
internal class Manhuarm(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHUARM, "manhuarmmtl.com", 20) {
	override val datePattern = "MMMM d, yyyy"
	override val sourceLocale: Locale = Locale.ENGLISH
	override val withoutAjax = true
}
