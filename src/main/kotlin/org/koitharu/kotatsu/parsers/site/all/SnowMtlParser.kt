package org.koitharu.kotatsu.parsers.site.all

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import java.util.*

private const val PAGE_SIZE = 24

@MangaSourceParser("SNOW_MTL", "SnowMtl", "en", ContentType.OTHER)
internal class SnowMtlParser(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.SNOW_MTL, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("snowmtl.ru")

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.POPULARITY
    )

    override val isSearchAvailable: Boolean = false

    private fun throwParseException(url: String, cause: Exception? = null): Nothing {
        throw ParseException("Failed to parse manga page", url, cause)
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()
        
        // Get the script content that contains the chapter data
        val scriptContent = doc.select("script").firstOrNull { it.data().contains("window.__NUXT__") }?.data() ?: throw ParseException("Chapter data not found")
        
        // Extract image URLs from the script content
        val imageUrls = scriptContent.let { script ->
            val imagesPattern = "\"images\":\\[(.*?)\\]".toRegex()
            val match = imagesPattern.find(script) ?: throw ParseException("Images data not found")
            val imagesJson = "[${match.groupValues[1]}]"
            parseJson(imagesJson).asJsonArray.map { it.asString }
        }

        return imageUrls.mapIndexed { i, url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                number = i,
                source = source
            )
        }
    }

    override suspend fun getList(offset: Int, query: String?, sortOrder: SortOrder): List<Manga> {
        if (query != null) {
            throw IllegalArgumentException("Search is not supported")
        }

        val url = buildString {
            append("$domainUrl/mangas")
            when (sortOrder) {
                SortOrder.UPDATED -> append("/last-update")
                SortOrder.POPULARITY -> append("/popular")
                else -> append("/last-update")  // fallback to updated
            }
            if (offset > 0) {
                append("?page=${(offset / PAGE_SIZE) + 1}")
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseJson(json: String): JsonElement {
        return Json.parseToJsonElement(json)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div.grid.grid-cols-1.sm\\:grid-cols-2.lg\\:grid-cols-3.xl\\:grid-cols-4.gap-8.p-6 > div")
            .map { div ->
                val href = div.selectFirst("a")?.attrAsRelativeUrl("href")
                    ?: throw ParseException("Link not found", div.baseUri())

                Manga(
                    id = generateUid(href),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(div.baseUri()),
                    coverUrl = div.selectFirst("a > div > img")?.src().orEmpty(),
                    title = div.selectFirst("div > a > h3")?.text().orEmpty(),
                    altTitle = null,
                    rating = RATING_UNKNOWN,
                    tags = emptySet(),
                    author = null,
                    state = null,
                    source = source,
                    isNsfw = false
                )
            }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val chaptersRoot = doc.selectFirst("section.bg-gray-800.rounded-lg.shadow-md.mt-8.p-6") 
            ?: throw ParseException("Chapters not found", manga.url)
            
        val chapters = chaptersRoot.select("ul > li > a").mapIndexed { index, link ->
            val href = link.attrAsRelativeUrl("href")
            val title = link.text()
            val number = title.substringAfter("Chapter ").substringBefore(" ").toFloatOrNull() ?: (index + 1).toFloat()

            MangaChapter(
                id = generateUid(href),
                title = title,
                number = number,
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }

        val title = doc.selectFirst("main > section:nth-child(1) > div > div.md\\:ml-8 > h1")?.text()?.trim()
        val coverUrl = doc.selectFirst("main > section:nth-child(1) > div > div.flex-shrink-0.md\\:w-1\\/3.mb-4.md\\:mb-0 > img")?.src()

        return manga.copy(
            title = title ?: manga.title,
            coverUrl = coverUrl ?: manga.coverUrl,
            chapters = chapters
        )
    }
}
