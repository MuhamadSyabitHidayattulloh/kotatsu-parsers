package org.koitharu.kotatsu.parsers.site.all

import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("SNOWMTL", "SnowMTL")
internal class SnowMtlParser(context: MangaLoaderContext) :
    LegacyPagedMangaParser(context, MangaParserSource.SNOWMTL, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("snowmtl.ru")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
        SortOrder.POPULARITY
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true
        )

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

        val doc = webClient.httpGet(url).parseHtml()

        return doc.select("div.novel-item").map { div ->
            val a = div.selectFirstOrThrow("a.novel-title")
            val href = a.attrAsRelativeUrl("href")

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = a.text(),
                altTitle = null,
                rating = div.selectFirst("span.novel-rating")?.text()?.toFloatOrNull() ?: RATING_UNKNOWN,
                coverUrl = div.selectFirst("img.novel-cover")?.src(),
                tags = div.select("span.novel-tag").mapNotNullToSet { span ->
                    MangaTag(
                        key = span.text().lowercase(),
                        title = span.text(),
                        source = source
                    )
                },
                state = when (div.selectFirst("span.novel-status")?.text()) {
                    "Ongoing" -> MangaState.ONGOING
                    "Completed" -> MangaState.FINISHED
                    else -> null
                },
                author = div.selectFirst("span.novel-author")?.text(),
                largeCoverUrl = null,
                description = null,
                altTitles = emptySet(),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val root = doc.body()

        return manga.copy(
            description = root.selectFirst("div.novel-description")?.html(),
            largeCoverUrl = root.selectFirst("img.novel-cover")?.src(),
            chapters = root.select("div.chapter-item").mapChapters { i, div ->
                val a = div.selectFirstOrThrow("a")
                MangaChapter(
                    id = generateUid(a.attrAsRelativeUrl("href")),
                    url = a.attrAsRelativeUrl("href"), 
                    name = a.text(),
                    number = i + 1f,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source
                )
            }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("div.chapter-content img").mapIndexed { i, img ->
            val url = img.src() ?: img.parseFailed("Image src not found")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
