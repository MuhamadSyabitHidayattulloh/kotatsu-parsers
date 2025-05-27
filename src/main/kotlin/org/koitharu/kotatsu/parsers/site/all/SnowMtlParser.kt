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
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING
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
    }

    override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
        val url = buildString {
            append(domain)
            append("/search")
            append("?")
            when (query.order) {
                SortOrder.POPULARITY -> append("sort_by=views")
                SortOrder.UPDATED -> append("sort_by=recent")
                SortOrder.ALPHABETICAL -> append("sort_by=name")
                SortOrder.RATING -> append("sort_by=rating")
                else -> append("sort_by=recent")
            }
            if (page > 1) {
                append("&page=")
                append(page)
            }
            query.criteria.find { it.field == SearchableField.TITLE_NAME }?.let { criteria ->
                when (criteria) {
                    is Match -> {
                        append("&query=")
                        append(criteria.value.toString())
                    }
                    is Include,
                    is Exclude,
                    is Range -> Unit
                }
            }
        }

        return webClient.httpGet(url)
            .parseHtml()
            .select("div.grid.grid-cols-1.sm\\:grid-cols-2.lg\\:grid-cols-3.xl\\:grid-cols-4.gap-8.p-6 > div")
            .map { div ->
                val href = div.selectFirst("a")?.attrAsRelativeUrl("href") ?: throw ParseException("Link not found", div.baseUri())

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

        val title = doc.selectFirst("div.md\\:ml-8 > h1")?.text()?.trim().orEmpty()
        val altTitle = doc.selectFirst("div.md\\:ml-8 > p:nth-of-type(1)")?.text()?.trim()
        val description = doc.selectFirst("div.md\\:ml-8 > p:nth-of-type(2)")?.text()?.trim()
        val author = doc.selectFirst("div.md\\:ml-8 > p:nth-of-type(3)")?.text()?.substringAfter(":")?.trim()
        val rating = doc.selectFirst("div.md\\:ml-8 > p:nth-of-type(4)")?.text()?.substringAfter(":")?.substringBefore("/")?.toFloatOrNull()?.let { (it / 5).coerceIn(0f, 1f) } ?: RATING_UNKNOWN
        val state = when (doc.selectFirst("div.md\\:ml-8 > p:nth-of-type(5)")?.text()?.substringAfter(":")?.trim()?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.COMPLETED
            else -> MangaState.UNKNOWN
        }
        val tags = doc.select("div.md\\:ml-8 > div.flex.flex-wrap.gap-2.mb-4 > span, div.md\\:ml-8 > div.flex.flex-wrap.gap-2 > span")
            .mapNotNull { it.text().trim().takeIf { it.isNotEmpty() }?.let { text ->
                MangaTag(text, text.lowercase(), source)
            }}.toSet()

        val chapters = doc.select("li.bg-gray-700.rounded-md.shadow-md.p-4.flex.justify-between.items-center").mapIndexed { index, li ->
            val url = li.selectFirst("a")?.attrAsRelativeUrl("href") ?: ""
            val title = li.selectFirst("a")?.text() ?: "Chapter ${index + 1}"
            val number = title.substringAfter("Chapter ", "${index + 1}").toFloatOrNull() ?: (index + 1).toFloat()
            val uploadDate = 0L // No timestamp available
            MangaChapter(
                id = generateUid(url),
                title = title,
                number = number,
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = uploadDate,
                branch = null,
                source = source
            )
        }

        return manga.copy(
            title = title,
            altTitle = altTitle,
            description = description,
            author = author,
            rating = rating,
            state = state,
            tags = tags,
            coverUrl = doc.selectFirst("div.flex-shrink-0.md\\:w-1\/3.mb-4.md\\:mb-0 > img")?.src() ?: manga.coverUrl,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val images = doc.select("div.comic-image-container > img")
        return images.mapIndexed { index, img ->
            MangaPage(
                index = index,
                imageUrl = img.src(),
                headers = emptyMap()
            )
        }
    }
}
