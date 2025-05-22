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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import java.util.*

private const val PAGE_SIZE = 24 // Based on the grid layout from the selector

@MangaSourceParser("SNOWMTL", "SnowMtl")
internal class SnowMtlParser(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.SNOWMTL, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("snowmtl.ru")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST
    )

    override val searchQueryCapabilities = MangaSearchQueryCapabilities(
        SearchCapability(
            field = SearchableField.TITLE_NAME,
            criteriaTypes = setOf(Match::class),
            isMultiple = false,
        )
    )    override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
        val url = buildString {
            append("https://").append(domain).append("/search")
            val sortQuery = when (query.sortOrder) {
                SortOrder.POPULARITY -> "?sort_by=views"
                SortOrder.UPDATED -> "?sort_by=recent"
                else -> "?example"
            }
            append(sortQuery)
            if (page > 1) {            append("&page=").append(page)
            }
            query.criteria.find { it.field == SearchableField.TITLE_NAME }?.let { criteria ->
                append("&q=").append(criteria.value)
            }
        }.toHttpUrl()

        return webClient.httpGet(url)
            .parseHtml()
            .select("div.grid.grid-cols-1.sm\\:grid-cols-2.lg\\:grid-cols-3.xl\\:grid-cols-4.gap-8.p-6 > div")
            .map { div ->
                val href = div.selectFirst("a")?.attrAsRelativeUrl("href") ?: div.throwParseException("Link not found")
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
                    isNsfw = source.contentType == ContentType.HENTAI,
                )
            }
    }    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        val chaptersRoot = doc.selectFirst("section.bg-gray-800.rounded-lg.shadow-md.mt-8.p-6 > div.overflow-y-auto.max-h-\\[500px\\] > ul")
            ?: throw ParseException("Chapters list not found", manga.url)
            
        return manga.copy(
            tags = emptySet(),
            author = null,
            description = doc.selectFirst("div.description")?.text(),
            chapters = chaptersRoot.select("li > a").mapChapters { a ->
                val href = a.attrAsAbsoluteUrl("href")
                MangaChapter(
                    id = generateUid(href),
                    url = href,
                    name = requireNotNull(a.text()) { "Chapter name is missing" },
                    number = 0f,
                    volume = 0,
                    branch = null,
                    uploadDate = 0L,
                    scanlator = null,
                    source = source,
                    title = null
                )
            }
        )
    }    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        
        // We need to evaluate JavaScript to get both image and text content
        // The comic-images-container has multiple divs, each containing an image and text bubble
        val pagesScript = """
            Array.from(document.querySelectorAll('#comic-images-container > div')).map(div => {
                const img = div.querySelector('img')?.src;
                const text = div.querySelector('div:nth-child(2)')?.textContent;
                return img;
            }).filter(x => x)
        """.trimIndent()
        
        val urls = context.evaluateJs(pagesScript)?.let { result ->
            result.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        } ?: throw ParseException("Pages not found", chapter.url)
        
        return urls.map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                source = source,
                preview = null
            )
        }
    }
}
