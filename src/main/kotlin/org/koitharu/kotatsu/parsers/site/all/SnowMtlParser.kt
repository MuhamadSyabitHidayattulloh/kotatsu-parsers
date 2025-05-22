package org.koitharu.kotatsu.parsers.site.all

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val PAGE_SIZE = 24 // Based on the grid layout from the selector

@MangaSourceParser("SNOWMTL", "SnowMtl")
internal class SnowMtlParser(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.SNOWMTL, PAGE_SIZE) {

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST
    )

    override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
        val url = buildString {
            append("https://snowmtl.ru/search")
            when (query.sortOrder) {
                SortOrder.POPULARITY -> append("?sort_by=views")
                SortOrder.UPDATED -> append("?sort_by=recent")
                else -> append("?example")
            }
            if (page > 1) {
                append("&page=").append(page)
            }
            if (query.tags.isNotEmpty()) {
                append("&tags=").append(query.tags.joinToString(","))
            }
        }.toHttpUrl().let { baseUrl ->
            if (query.term.isNotEmpty()) {
                baseUrl.newBuilder()
                    .addQueryParameter("q", query.term)
                    .build()
            } else {
                baseUrl
            }
        }

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
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl()).parseHtml()
        
        val chaptersRoot = doc.selectFirst("section.bg-gray-800.rounded-lg.shadow-md.mt-8.p-6 > div.overflow-y-auto.max-h-\\[500px\\] > ul")
            ?: doc.throwParseException("Chapters list not found")
            
        return manga.copy(
            tags = emptySet(), // You can implement tag parsing if the site has them
            author = null, // You can implement author parsing if available
            description = doc.selectFirst("div.description")?.text(),
            chapters = chaptersRoot.select("li > a").mapChapters { a ->
                val href = a.attrAsRelativeUrl("href")
                MangaChapter(
                    id = generateUid(href),
                    name = a.text(),
                    url = href,
                    number = 0f, // You can parse chapter number if available
                    uploadDate = 0L, // You can parse upload date if available
                    source = source,
                    scanlator = null,
                    branch = null
                )
            }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl()).parseHtml()
        
        // We need to evaluate JavaScript to get both image and text content
        val imageScript = "document.querySelector('#comic-images-container > div:nth-child(1) > img')?.src"
        val textScript = "document.querySelector('#comic-images-container > div:nth-child(1) > div:nth-child(2)')?.textContent"
        
        val imageUrl = context.evaluateJs(imageScript) ?: throw ParseException("Image not found", chapter.url)
        val textContent = context.evaluateJs(textScript)
        
        return listOf(
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                source = source,
                preview = null
            )
        )
    }
}
