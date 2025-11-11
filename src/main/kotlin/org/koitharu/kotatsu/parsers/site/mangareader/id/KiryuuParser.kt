package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Headers.Companion.headersOf
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class KiryuuParser(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.KIRYUU, "kiryuu03.com", pageSize = 50, searchPageSize = 10) {

    override val listUrl = "/manga/"

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )

    // Override description selector for WordPress block theme
    override val detailsDescriptionSelector = "[itemprop='description'], [itemprop='description'] p, .entry-content p, .synopsis, .description, [class*='description'], [class*='synopsis'], .summary, .wp-block-paragraph, .content p"

    // Override to handle paginated search instead of AJAX search
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            // Handle paginated search using WordPress URL structure
            // Example URL: /manga/page/2/?s=query&the_type=comic,manga,manhua,manhwa&the_orderby=date

            // Map SortOrder to the `the_orderby` parameter value
            val orderByParam = when (order) {
                SortOrder.POPULARITY -> "popular"
                SortOrder.NEWEST -> "latest"
                SortOrder.ALPHABETICAL -> "title"
                else -> "date" // Default to date for RELEVANCE and UPDATED
            }

            val searchUrl = buildString {
                append("https://$domain$listUrl")
                if (page > 1) {
                    append("page/$page/")
                }
                // Use 's' for the search query, which is standard for WordPress
                append("?s=${filter.query.urlEncoded()}")
                append("&the_type=comic%2Cmanga%2Cmanhua%2Cmanhwa") // Filter out novels as requested
                append("&the_orderby=$orderByParam")
            }

            val document = webClient.httpGet(searchUrl).parseHtml()
            return parseMangaList(document)
        }

        // For non-search requests, use the default implementation from the parent class
        return super.getListPage(page, order, filter)
    }

    // Override parsing for new Kiryuu structure with multiple fallback approaches
    override fun parseMangaList(docs: Document): List<Manga> {
        // Get all manga links and group them by URL since title and image are in separate <a> tags
        val allMangaLinks = docs.select("a[href*='/manga/']:not([href*='chapter']):not([href*='page='])")
            .filter { a ->
                val href = a.attr("href")
                // Make sure this is a manga detail link, not chapter or pagination
                href.matches(Regex(".*/manga/[^/]+/?$"))
            }

        // Group links by URL to combine image and title links
        val mangaMap = mutableMapOf<String, MangaData>()

        for (link in allMangaLinks) {
            val url = link.attrAsRelativeUrlOrNull("href") ?: continue

            val data = mangaMap.getOrPut(url) { MangaData(url) }

            // Check if this link has an image
            val img = link.selectFirst("img")
            if (img != null) {
                data.coverUrl = img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
            }

            // Check if this link has a title
            val title = link.selectFirst("h2, h3, h1, .title, [class*='title']")?.text()?.trim()
                ?: link.attr("title")?.takeIf { it.isNotBlank() }
                ?: link.attr("aria-label")?.takeIf { it.isNotBlank() }
                ?: link.ownText().trim().takeIf { it.isNotEmpty() && it.length > 2 }

            if (title != null) {
                data.title = title
            }

            // Look for rating in this link or nearby elements
            val rating = link.selectFirst("[class*='rating'], .score, [class*='score']")?.text()?.trim()
                ?: link.parent()?.selectFirst("[class*='rating'], .score, [class*='score']")?.text()?.trim()
                ?: link.nextElementSibling()?.selectFirst("[class*='rating'], .score, [class*='score']")?.text()?.trim()

            if (rating != null) {
                data.rating = Regex("(\\d+(?:\\.\\d+)?)").find(rating)?.value?.toFloatOrNull() ?: RATING_UNKNOWN
            }
        }

        // Convert to Manga objects
        return mangaMap.values.mapNotNull { data ->
            if (data.title == null) return@mapNotNull null // Must have a title

            Manga(
                id = generateUid(data.url),
                url = data.url,
                title = data.title!!,
                altTitles = emptySet(),
                publicUrl = "https://$domain${data.url}",
                rating = data.rating,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = data.coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    private data class MangaData(
        val url: String,
        var title: String? = null,
        var coverUrl: String? = null,
        var rating: Float = RATING_UNKNOWN
    )

    // Override chapter parsing for Kiryuu's structure - uses AJAX to load chapters
    override suspend fun getDetails(manga: Manga): Manga {
        val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        // Extract manga_id from the bookmark button or other elements with manga_id
        val mangaIdMatch = Regex("manga_id=(\\d+)").find(docs.html())
        val mangaId = mangaIdMatch?.groupValues?.get(1)

        var chapters = emptyList<MangaChapter>()

        if (mangaId != null) {
            try {
                // Use AJAX endpoint to get chapters JSON
                val chaptersUrl = "https://$domain/wp-admin/admin-ajax.php?action=get_chapters&manga_id=$mangaId"
                val response = webClient.httpGet(chaptersUrl)
                val jsonResponse = response.parseJson()

                val success = jsonResponse.getBoolean("success")
                if (success) {
                    val data = jsonResponse.getJSONArray("data")
                    chapters = (0 until data.length()).mapNotNull { i ->
                        val chapter = data.getJSONObject(i)
                        val title = chapter.getString("title")
                        val url = chapter.getString("url")
                        val chapterNum = Regex("Chapter\\s+(\\d+(?:\\.\\d+)?)").find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1).toFloat()

                        MangaChapter(
                            id = generateUid(url),
                            title = title,
                            url = url.removePrefix("https://$domain"),
                            number = chapterNum,
                            volume = 0,
                            scanlator = null,
                            uploadDate = 0L,
                            branch = null,
                            source = source,
                        )
                    }.sortedBy { it.number }
                }
            } catch (e: Exception) {
                // Fall back to HTML parsing if AJAX fails
            }
        }

        // If AJAX failed, try to find chapters in the static HTML (fallback)
        if (chapters.isEmpty()) {
            chapters = docs.select("a[href*='chapter']").filter { element ->
                val href = element.attr("href")
                href.contains("/chapter-") && Regex("/chapter-\\d+\\.\\d+").containsMatchIn(href)
            }.mapNotNull { element ->
                val url = element.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
                if (!url.contains("/chapter-")) return@mapNotNull null

                val chapterText = element.text().trim()
                val title = when {
                    chapterText.isNotEmpty() && chapterText.contains("Chapter", ignoreCase = true) -> chapterText
                    chapterText.isNotEmpty() && chapterText.matches(Regex("\\d+.*")) -> "Chapter $chapterText"
                    else -> {
                        // Extract chapter number from URL - handle the .XXXXXX suffix pattern
                        val chapterNum = Regex("/chapter[.-](\\d+)(?:\\.\\d+)?").find(url)?.groupValues?.get(1)
                            ?: Regex("(?:chapter|ch)[.-](\\d+)").find(url)?.groupValues?.get(1)
                            ?: Regex("/(\\d+)/?$").find(url)?.groupValues?.get(1)
                        chapterNum?.let { "Chapter $it" }
                    }
                } ?: return@mapNotNull null

                // Extract chapter number for sorting
                val chapterNumber = Regex("(\\d+(?:\\.\\d+)?)").find(title)?.value?.toFloatOrNull() ?: 0f

                MangaChapter(
                    id = generateUid(url),
                    title = title,
                    url = url,
                    number = chapterNumber,
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                )
            }.distinctBy { it.url }.sortedBy { it.number }
        }

        // If still no chapters found, create a single read button
        if (chapters.isEmpty()) {
            val readButton = docs.select("a").find { element ->
                val text = element.text().lowercase()
                val href = element.attr("href")
                (text.contains("read") || text.contains("chapter"))
                    && href.contains("/chapter") && !href.contains("/manga/")
            }

            if (readButton != null) {
                val url = readButton.attrAsRelativeUrlOrNull("href")
                if (url != null) {
                    chapters = listOf(
                        MangaChapter(
                            id = generateUid(url),
                            title = "Chapter 1",
                            url = url,
                            number = 1f,
                            volume = 0,
                            scanlator = null,
                            uploadDate = 0L,
                            branch = null,
                            source = source,
                        )
                    )
                }
            }
        }

        return parseInfo(docs, manga, chapters)
    }

    // Override chapter image parsing for Kiryuu's structure
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        // Find the section containing all chapter images
        val imageSection = docs.selectFirst("section[data-image-data]")
            ?: docs.selectFirst("section img")?.parent()
            ?: docs.selectFirst("[class*='chapter'] img")?.parent()
            ?: docs.body()

        // Extract all images from the section
        val images = imageSection.select("img").mapNotNull { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() }
                ?: img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
            src?.let { it.toAbsoluteUrl(docs.location()) }
        }

        return images.mapIndexed { index, url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}
