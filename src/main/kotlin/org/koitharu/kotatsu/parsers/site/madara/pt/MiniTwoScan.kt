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

    // MiniTwoScan uses a custom chapter list structure
    override val selectTestAsync = "div.list-chapter div.chapter-item"
    override val selectChapter = "div.list-chapter div.chapter-item"

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val existingChapters = doc.select(selectChapter)
        if (existingChapters.isEmpty()) {
            return emptyList()
        }

        // Extract manga slug from current manga URL: /manga/{slug}
        val mangaSlug = manga.url.removeSuffix("/").substringAfterLast("/")
        println("MiniTwoScan: mangaSlug=$mangaSlug")
        if (mangaSlug.isEmpty()) {
            return emptyList()
        }

        // Find max chapter number from the visible list
        var maxChapter = 0f
        println("MiniTwoScan: Found ${existingChapters.size} potential chapter elements")
        for (element in existingChapters) {
            val linkElement = element.selectFirst("span.chapter a") ?: continue
            val href = linkElement.attr("href")
            
            if (!href.contains("/$mangaSlug/")) {
                println("MiniTwoScan: Skipping chapter with href '$href' (doesn't contain slug)")
                continue
            }
            val title = linkElement.text().trim()
            val chapterNumber = title.replace("Capítulo", "").trim().toFloatOrNull()
            println("MiniTwoScan: Processing chapter '$title', href='$href', parsedNumber=$chapterNumber")
            
            if (chapterNumber != null && chapterNumber > maxChapter) {
                maxChapter = chapterNumber
            }
        }
        println("MiniTwoScan: maxChapter found=$maxChapter")
        if (maxChapter == 0f) {
            return emptyList()
        }

        // Generate normalized chapter URLs per manga slug:
        // /manga/{slug}/capitulo-{NN}/
        return (1..maxChapter.toInt()).map { chapterNum ->
            val chapterNumFormatted = if (chapterNum < 10) "%02d".format(chapterNum) else chapterNum.toString()
            val chapterUrl = "/manga/$mangaSlug/capitulo-$chapterNumFormatted/"
            MangaChapter(
                id = generateUid(chapterUrl),
                title = "Capítulo $chapterNum",
                number = chapterNum.toFloat(),
                volume = 0,
                url = chapterUrl.toRelativeUrl(domain),
                uploadDate = 0L,
                source = source,
                scanlator = null,
                branch = null,
            )
        }
    }
}
