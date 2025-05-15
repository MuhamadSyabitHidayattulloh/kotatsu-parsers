package org.koitharu.kotatsu.parsers.site.all

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("SNOWMTL", "SnowMTL")
internal class SnowMtlParser(context: MangaLoaderContext) : 
    LegacyPagedMangaParser(context, MangaParserSource.SNOWMTL, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("snowmtl.ru")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isLocaleSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableLocales = setOf(
                Locale.ENGLISH,
                Locale.CHINESE,
                Locale.KOREAN
            )
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/search?page=")
            append(page.toString())

            if (!filter.query.isNullOrEmpty()) {
                append("&query=")
                append(filter.query.urlEncoded())
            }

            if (filter.locale != null) {
                append("&lang=")
                append(filter.locale.language)
            }

            append("&sort=")
            append(
                when (order) {
                    SortOrder.UPDATED -> "updated"
                    SortOrder.POPULARITY -> "popular"
                    SortOrder.ALPHABETICAL -> "az"
                    else -> "updated"
                }
            )
        }

        return webClient.httpGet(url).parseHtml()
            .select(".comic-list .comic-item")
            .map { div ->
                val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
                Manga(
                    id = generateUid(href),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    coverUrl = div.selectFirst("img")?.src(),
                    title = div.selectFirstOrThrow(".title").text().trim(),
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    tags = emptySet(),
                    description = null,
                    state = null,
                    author = null,
                    source = source
                )
            }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val root = doc.selectFirstOrThrow(".comic-detail")

        return manga.copy(
            description = root.selectFirst(".synopsis")?.text()?.trim(),
            altTitles = root.select(".alt-titles span").mapToSet { it.text().trim() },
            tags = root.select(".genres a").mapToSet { 
                MangaTag(
                    title = it.text().trim(),
                    key = it.attrAsRelativeUrl("href"),
                    source = source
                )
            },
            chapters = doc.select(".chapter-list .chapter-item").mapChapters { i, div ->
                val a = div.selectFirstOrThrow("a")
                val href = a.attrAsRelativeUrl("href")
                MangaChapter(
                    id = generateUid(href),
                    name = a.text().trim(),
                    number = i + 1f,
                    url = href,
                    uploadDate = div.selectFirst(".date")?.text()?.let {
                        parseChapterDate(it)
                    } ?: 0L,
                    source = source,
                    scanlator = div.selectFirst(".scanlator")?.text()?.trim(),
                    branch = div.selectFirst(".language")?.text()?.trim()
                )
            }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select(".page-content img").mapIndexed { i, img ->
            MangaPage(
                id = generateUid(chapter.id + i),
                url = img.src() ?: img.parseFailed("Image src not found"),
                preview = null,
                source = source
            )
        }
    }

    private fun parseChapterDate(date: String): Long {
        return try {
            val regex = """(\d+)\s+(minute|hour|day|week|month|year)s?\s+ago""".toRegex()
            val match = regex.find(date) ?: return 0L
            val (value, unit) = match.destructured
            val calendar = Calendar.getInstance()
            
            when (unit) {
                "minute" -> calendar.add(Calendar.MINUTE, -value.toInt())
                "hour" -> calendar.add(Calendar.HOUR, -value.toInt()) 
                "day" -> calendar.add(Calendar.DAY_OF_MONTH, -value.toInt())
                "week" -> calendar.add(Calendar.WEEK_OF_MONTH, -value.toInt())
                "month" -> calendar.add(Calendar.MONTH, -value.toInt())
                "year" -> calendar.add(Calendar.YEAR, -value.toInt())
            }
            
            calendar.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }
}
