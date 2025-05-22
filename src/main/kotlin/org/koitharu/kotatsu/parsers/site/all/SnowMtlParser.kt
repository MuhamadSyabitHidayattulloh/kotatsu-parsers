package org.koitharu.kotatsu.parsers.site.all

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.Match
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import java.util.*

private const val PAGE_SIZE = 24 // Based on the grid layout from the selector

@MangaSourceParser("SNOW_MTL", "SnowMtl", "", ContentType.OTHER)
internal class SnowMtlParser(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.SNOW_MTL, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("snowmtl.ru")
      override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    override val searchQueryCapabilities = MangaSearchQueryCapabilities(
        SearchCapability(
            field = SearchableField.TITLE_NAME,
            criteriaTypes = setOf(Match::class),
            isMultiple = false
        )
    )

    private fun throwParseException(url: String, cause: Exception? = null): Nothing {
        throw ParseException("Failed to parse manga page", url, cause)
    }    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = urlBuilder()
            .host(domain)
            .addPathSegment("search")
            .apply {
                when (order) {
                    SortOrder.POPULARITY -> addQueryParameter("sort_by", "views")
                    SortOrder.UPDATED -> addQueryParameter("sort_by", "recent")
                    else -> addQueryParameter("sort_by", "recent")
                }
                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
                if (!filter.query.isNullOrEmpty()) {
                    addQueryParameter("q", filter.query)
                }
            }.build()

        return webClient.httpGet(url)
            .parseHtml()            .select("div.grid.grid-cols-1.sm\\:grid-cols-2.lg\\:grid-cols-3.xl\\:grid-cols-4.gap-8.p-6 > div")
            .map { div ->
                val a = div.selectFirst("a") ?: throw ParseException("Link not found", div.baseUri())
                val href = a.attrAsRelativeUrl("href")
                val cover = div.selectFirst("img")?.absUrl("src").orEmpty()
                val title = a.text()
                
                Manga(
                    id = generateUid(href),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    title = title,
                    altTitle = null,
                    coverUrl = cover,
                    tags = emptySet(),
                    state = null,
                    author = null,
                    rating = RATING_UNKNOWN,
                    isNsfw = false,
                    source = source,
                )
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
                    isNsfw = source.contentType == ContentType.HENTAI
                )            }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        val chaptersRoot = doc.selectFirst("div.chapter-list") ?: throw ParseException("Chapters not found", manga.url)
        val chapters = chaptersRoot.select("div.chapter-item").mapChapters { div ->
            val link = div.selectFirst("a") ?: throw ParseException("Chapter link not found", manga.url)
            val href = link.attrAsRelativeUrl("href")
            val title = link.text()
            val number = title.substringAfter("Chapter ").substringBefore(" ").toFloatOrNull() ?: 0f
            
            MangaChapter(
                id = generateUid(href),
                url = href,
                name = title,
                number = number,
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }

        val authorDiv = doc.selectFirst("div.author-info")
        val author = authorDiv?.text()?.trim()
          val tagsDiv = doc.selectFirst("div.tags")
        val tags = tagsDiv?.select("a")?.mapNotNull { a ->
            val tagName = a.text().trim()
            MangaTag(
                title = tagName,
                key = tagName.lowercase(),
                source = source
            )
        }?.toSet() ?: emptySet()

        return manga.copy(
            authors = setOfNotNull(author),
            tags = tags,
            description = doc.selectFirst("div.description")?.text()?.trim(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()        return doc.select("div.reader-images img").mapNotNull { img ->
            val url = img.absUrl("src").takeIf { it.isNotEmpty() } ?: img.absUrl("data-src")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
