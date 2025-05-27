package org.koitharu.kotatsu.parsers.site.all

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@MangaSourceParser("SNOWMTL", "SnowMTL", "ru")
internal class SnowMtlParser(context: MangaLoaderContext) : LegacyPagedMangaParser(
    context = context,
    source = MangaParserSource.SNOWMTL,
    pageSize = 20
) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("https://snowmtl.ru")

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.POPULARITY_TODAY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        SortOrder.POPULARITY,
        SortOrder.UPDATED
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        val url = when {
            query != null -> "https://snowmtl.ru/search?query=${query}&page=$page"
            order == SortOrder.UPDATED -> "https://snowmtl.ru/search?sort_by=recent&page=$page"
            order in setOf(SortOrder.POPULARITY_TODAY, SortOrder.POPULARITY_WEEK, SortOrder.POPULARITY_MONTH, SortOrder.POPULARITY) ->
                "https://snowmtl.ru/search?sort_by=views&page=$page"
            else -> "https://snowmtl.ru/?page=$page"
        }

        val doc = context.http.getDocument(url)
        val selector = if (query != null || url.contains("search")) {
            "div.bg-gray-700.rounded-md.shadow-md.flex.flex-col"
        } else {
            "div.bg-gray-700.rounded-lg.shadow-md.w-40"
        }

        return doc.select(selector).mapNotNull { element ->
            val anchor = element.selectFirst("a") ?: return@mapNotNull null
            val img = element.selectFirst("img")
            val titleElement = element.selectFirst("h") ?: element.selectFirst("h3")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            val url = anchor.absUrl("href")
            val thumbnailUrl = img?.absUrl("src") ?: ""

            Manga(
                title = title,
                url = url,
                thumbnailUrl = thumbnailUrl
            )
        }
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = context.http.getDocument(manga.url)

        val title = doc.selectFirst("div.md\\:ml-8 > h1")?.text()?.trim() ?: manga.title
        val thumbnail = doc.selectFirst("div.flex-shrink-0.md\\:w-1\\/3.mb-4.md\\:mb-0 > img")?.absUrl("src") ?: manga.thumbnailUrl
        val description = doc.select("div.md\\:ml-8 > p:nth-of-type(2)").text().trim()
        val author = doc.select("div.md\\:ml-8 > p:nth-of-type(3)").text().trim()

        val genreTags = doc.select("div.md\\:ml-8 > div.flex.flex-wrap.gap-2.mb-4 > span").map { it.text().trim() }
        val otherTags = doc.select("div.md\\:ml-8 > div.flex.flex-wrap.gap-2 > span").map { it.text().trim() }
        val tags = genreTags + otherTags

        val chapters = doc.select("li.bg-gray-700.rounded-md.shadow-md.p-4.flex.justify-between.items-center").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val chapterUrl = a.absUrl("href")
            val chapterName = a.text().trim()
            val uploadedDate = li.selectFirst("span")?.text()?.trim()

            MangaChapter(
                name = chapterName,
                url = chapterUrl,
                uploadedDate = uploadedDate
            )
        }

        return manga.copy(
            title = title,
            thumbnailUrl = thumbnail,
            description = description,
            author = author,
            tags = tags,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = context.http.getDocument(chapter.url)
        val imageElements = doc.select("div.comic-image-container > img")

        return imageElements.mapIndexed { index, element ->
            MangaPage(index, imageUrl = element.absUrl("src"))
        }
    }
}