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
        SortOrder.POPULARITY
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
            append("https://")
            append(domain)
            append("/search")
            append("?")
            when (query.order) {
                SortOrder.POPULARITY -> append("sort_by=views")
                SortOrder.UPDATED -> append("sort_by=recent")
                else -> append("sort_by=recent")
            }
            if (page > 1) {
                append("&page=")
                append(page)
            }
            query.criteria.find { it.field == SearchableField.TITLE_NAME }?.let { criteria ->                when (criteria) {
                    is Match -> {
                        append("&query=")
                        append(criteria.value.toString())
                    }
                    is Include,
                    is Exclude,
                    is Range -> Unit // Not supported for this field
                }
            }
        }

        return webClient.httpGet(url)
            .parseHtml()
            .select("div.grid.grid-cols-1.sm\\:grid-cols-2.lg\\:grid-cols-3.xl\\:grid-cols-4.gap-8.p-6 > div")
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
        val altTitle = doc.selectFirst("main > section:nth-child(1) > div > div.md\\:ml-8 > p.text-gray-400")?.text()?.trim()
        val author = doc.selectFirst("main > section:nth-child(1) > div > div.md\\:ml-8 > p:contains(Author)")?.text()?.substringAfter("Author:")?.trim()
        val description = doc.selectFirst("main > section:nth-child(1) > div > div.md\\:ml-8 > p.mt-4.text-gray-300")?.text()?.trim()
        val ratingText = doc.selectFirst("main > section:nth-child(1) > div > div.md\\:ml-8 > p:contains(Rating)")?.text()?.trim()
        val statusText = doc.selectFirst("main > section:nth-child(1) > div > div.md\\:ml-8 > p:contains(Status)")?.text()?.substringAfter("Status:")?.trim()
        val tags = doc.select("main > section:nth-child(1) > div > div.md\\:ml-8 > div.flex.flex-wrap.gap-2 > a")
            .mapNotNull { tag ->
                tag.text().trim().takeUnless { it.isBlank() }?.let {
                    MangaTag(
                        title = it,
                        key = it.lowercase(),
                        source = source,
                    )
                }
            }.toSet()
            val rating = ratingText?.let {
            try {
                val ratingValue = it.substringAfter("Rating:").substringBefore("/").trim().toFloat()
                (ratingValue / 5f).coerceIn(0f, 1f)
            } catch (e: NumberFormatException) {
                RATING_UNKNOWN
            }
        } ?: RATING_UNKNOWN

        val state = when (statusText?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "dropped" -> MangaState.ABANDONED
            else -> null
        }
        return manga.copy(
            title = title ?: manga.title,
            altTitles = setOfNotNull(altTitle),
            authors = setOfNotNull(author),
            description = description,
            coverUrl = coverUrl ?: manga.coverUrl,
            chapters = chapters,
            rating = rating,
            tags = tags,
            state = state
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        
        return buildList {
            doc.select("#comic-images-container > div").forEach { div ->
                div.selectFirst("img")?.let { img ->
                    val url = img.absUrl("src").takeIf { it.isNotEmpty() } ?: img.absUrl("data-src")
                    if (url.isNotEmpty()) {
                        add(MangaPage(
                            id = generateUid(url),
                            url = url,
                            preview = null,
                            source = source
                        ))
                    }
                }
                
                div.selectFirst("div:nth-child(2)")?.let { textDiv ->
                    val text = textDiv.text()
                    if (text.isNotEmpty()) {
                        // Convert text to image using a data URL
                        val dataUrl = "data:text/plain;base64," + java.util.Base64.getEncoder().encodeToString(text.toByteArray())
                        add(MangaPage(
                            id = generateUid(dataUrl),
                            url = dataUrl,
                            preview = null,
                            source = source
                        ))
                    }
                }
            }
        }
    }
}
