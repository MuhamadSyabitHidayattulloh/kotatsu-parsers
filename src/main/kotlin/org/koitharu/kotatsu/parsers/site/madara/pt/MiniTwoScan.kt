package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.toRelativeUrl

@MangaSourceParser("MINITWOSCAN", "MiniTwoScan", "pt")
internal class MiniTwoScan(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MINITWOSCAN, "minitwoscan.com") {

    override val withoutAjax = true

    // MiniTwoScan uses a custom chapter list structure
    override val selectTestAsync = "div.list-chapter div.chapter-item"
    override val selectChapter = "div.list-chapter div.chapter-item"

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val items = doc.select(selectChapter)
        if (items.isEmpty()) {
            return emptyList()
        }

        val chapters = items.mapNotNull { el ->
            val link = el.selectFirst("span.chapter a") ?: el.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isNullOrBlank()) return@mapNotNull null

            val chapterUrl = href.toRelativeUrl(domain)
            val rawTitle = link.text()?.trim().orEmpty()
            val number = parseChapterNumber(rawTitle)
                ?: parseChapterNumber(chapterUrl)
                ?: 0f

            MangaChapter(
                id = generateUid(chapterUrl),
                title = if (rawTitle.isNotEmpty()) rawTitle else defaultTitle(number),
                number = number,
                volume = 0,
                url = chapterUrl,
                uploadDate = 0L,
                source = source,
                scanlator = null,
                branch = null,
            )
        }

        // Deduplicate and sort by parsed number (fallback by URL to keep stable order)
        return chapters
            .distinctBy { it.url }
            .sortedWith(compareBy<MangaChapter> { if (it.number > 0f) it.number else Float.MAX_VALUE }
                .thenBy { it.url })
    }

    private fun defaultTitle(number: Float): String =
        if (number > 0f) "Capítulo ${if (number % 1f == 0f) number.toInt() else number}" else "Capítulo"

    private fun parseChapterNumber(source: String): Float? {
        if (source.isBlank()) return null
        val s = source.lowercase()

        // Try patterns in order: "Capítulo 12.5", "capitulo-012", trailing number
        val p1 = Regex("(cap[ií]tulo|capitulo|chapter)[^\\d]*(\\d+(?:\\.\\d+)?)").find(s)
        val p2 = Regex("capitulo-?(\\d+(?:\\.\\d+)?)").find(s)
        val p3 = Regex("(\\d+(?:\\.\\d+)?)$").find(s)

        val raw = (p1?.groupValues?.getOrNull(2))
            ?: (p2?.groupValues?.getOrNull(1))
            ?: (p3?.groupValues?.getOrNull(1))
            ?: return null

        // Drop leading zeros safely, keep decimal if present
        val cleaned = raw.trim().replaceFirst(Regex("^0+(\\d)"), "$1")
        return cleaned.toFloatOrNull()
    }
}
