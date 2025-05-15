package org.koitharu.kotatsu.parsers.site.all

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import java.util.*

@MangaSourceParser("SNOWMTL", "Snow MTL", type = ContentType.NOVEL)
internal class SnowMtlParser(context: MangaLoaderContext) :
    LegacyPagedMangaParser(context, MangaParserSource.SNOWMTL, 24) {

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RATING,
        SortOrder.POPULARITY
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)

            if (!filter.query.isNullOrEmpty()) {
                append("/search?query=")
                append(filter.query.urlEncoded())
            } else {
                append("/novel-list")
                append("?sorting=")
                append(
                    when (order) {
                        SortOrder.UPDATED -> "latest"
                        SortOrder.ALPHABETICAL -> "az"
                        SortOrder.RATING -> "rating"
                        SortOrder.POPULARITY -> "popularity"
                        else -> "latest"
                    }
                )
            }

            append("&page=")
            append(page.toString())
        }

        val parseList = webClient.httpGet(url).parseHtml()
            .select("div.novel-item").map { element ->
                val link = element.selectFirstOrThrow("a.novel-title")
                Manga(
                    id = generateUid(link.attrAsRelativeUrl("href")),
                    url = link.attrAsRelativeUrl("href"),
                    publicUrl = link.absUrl("href"),
                    title = link.text(),
                    altTitles = emptySet(),
                    rating = element.selectFirst(".rating-value")?.ownText()?.toFloatOrNull() ?: RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = element.selectFirst("img")?.src(),
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source
                )
            }

        return parseList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        return manga.copy(
            description = doc.selectFirst(".summary-content")?.text(),
            state = when(doc.selectFirst(".status")?.text()?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                else -> null
            },
            tags = doc.select(".genres a").mapNotNullToSet { a ->
                MangaTag(
                    title = a.text(),
                    key = a.attr("href").substringAfterLast("/"),
                    source = source
                )
            },
            authors = doc.select(".author a").mapNotNullToSet { it.text() }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // Since this is for novels, implement appropriate page parsing
        // This is just a placeholder implementation
        return emptyList()
    }
}
