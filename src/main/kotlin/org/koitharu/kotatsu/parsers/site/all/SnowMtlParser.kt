package org.koitharu.kotatsu.parsers.site.all

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

private const val PAGE_SIZE = 24

@MangaSourceParser("SNOW_MTL", "SnowMtl", "en", ContentType.OTHER)
internal class SnowMtlParser(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.SNOW_MTL, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("snowmtl.ru")

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.POPULARITY
    )

    private val baseUrl = "https://snowmtl.ru"

    override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
        val url = buildString {
            append(baseUrl)
            append("/mangas")
            when (query.sortOrder) {
                SortOrder.UPDATED -> append("/last-update")
                SortOrder.POPULARITY -> append("/popular")
                else -> append("/last-update")
            }
            if (page > 1) {
                append("?page=$page")
            }
        }
        
        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()
        
        val scriptContent = doc.select("script").firstOrNull { it.data().contains("window.__NUXT__") }?.data() 
            ?: throw ParseException("Chapter data not found", chapter.url)
        
        val imagesJson = scriptContent.let { script ->
            val imagesPattern = "\"images\":\\[(.*?)\\]".toRegex()
            val match = imagesPattern.find(script) ?: throw ParseException("Images data not found", chapter.url)
            "[${match.groupValues[1]}]"
        }

        val images = JSONArray(imagesJson)
        return (0 until images.length()).map { i ->
            val imageUrl = images.getString(i)
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        val mangaList = doc.select("div.series-grid div.series-box")
        return mangaList.mapNotNull { element ->
            val link = element.selectFirst("a.series-title") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            val title = link.text().trim()
            val cover = element.selectFirst("img")?.attrAsAbsoluteUrl("src")

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(baseUrl),
                title = title,
                altTitle = null,
                rating = RATING_UNKNOWN,
                isNsfw = false,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                author = null,
                source = source
            )
        }
    }
}
