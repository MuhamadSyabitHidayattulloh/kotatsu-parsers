package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("MINITWOSCAN", "MiniTwoScan", "pt")
internal class MiniTwoScan(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MINITWOSCAN, "minitwoscan.com") {

    override val withoutAjax = true

    // Use correct selectors for MiniTwoScan's chapter structure
    override val selectTestAsync = "div.list-chapter div.chapter-item"
    override val selectChapter = "div.list-chapter div.chapter-item"

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val existingChapters = doc.select(selectChapter)
        if (existingChapters.isEmpty()) {
            return emptyList()
        }

        // Parse existing chapters to find the highest number and URL pattern
        var maxChapter = 0f
        var baseUrl = ""
        var mangaSlug = ""
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

        for (element in existingChapters) {
            val linkElement = element.selectFirst("span.chapter a") ?: continue
            val href = linkElement.attr("href")
            val title = linkElement.text().trim()

            // Extract chapter number from title like "Capítulo 141"
            val chapterNumber = title.replace("Capítulo", "").trim().toFloatOrNull()
            if (chapterNumber != null && chapterNumber > maxChapter) {
                maxChapter = chapterNumber

                // Extract base URL and manga slug from href
                // URL pattern: https://minitwoscan.com/manga/manga-name/capitulo-141/
                val urlParts = href.split("/")
                val chapterIndex = urlParts.indexOfLast { it.startsWith("capitulo-") }
                if (chapterIndex > 0) {
                    baseUrl = urlParts.take(chapterIndex).joinToString("/") + "/capitulo-"
                    mangaSlug = urlParts[chapterIndex - 1]
                }
            }
        }

        if (maxChapter == 0f || baseUrl.isEmpty()) {
            return emptyList()
        }

        // Generate all chapters from 1 to maxChapter
        return (1..maxChapter.toInt()).map { chapterNum ->
            val chapterUrl = "$baseUrl$chapterNum/"
            MangaChapter(
                id = generateUid(chapterUrl),
                title = "Capítulo $chapterNum",
                number = chapterNum.toFloat(),
                volume = 0,
                url = chapterUrl.toRelativeUrl(domain),
                uploadDate = 0L, // No date available for generated chapters
                source = source,
                scanlator = null,
                branch = null,
            )
        }
    }

}
