package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGALIVRE", "Manga Livre", "pt")
internal class MangaLivre(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGALIVRE, "mangalivre.tv") {
    override val datePattern = "MMMM dd, yyyy"
    override val withoutAjax = true
    override val stylePage = ""

    private val captureAllPattern = Regex(".*")

    private fun buildPathPattern(path: String): Regex {
        val clean = path.removePrefix("/").removeSuffix("/")
        return if (clean.isEmpty()) {
            Regex("^https?://${Regex.escape(domain)}/?(\\?.*)?$")
        } else {
            Regex("^https?://${Regex.escape(domain)}/${Regex.escape(clean)}(/|\\?.*)?$")
        }
    }

    private suspend fun captureDocument(
        initialUrl: String,
        preferredMatch: Regex? = null,
        timeoutMs: Long = 15000L,
        allowBrowserAction: Boolean = true,
    ): Document {
        println("DEBUG: captureDocument loading $initialUrl with captureWebViewUrls")

        // First, load the webview and let it handle redirects
        val capturedUrls = try {
            context.captureWebViewUrls(
                pageUrl = initialUrl,
                urlPattern = captureAllPattern,
                timeout = timeoutMs
            )
        } catch (e: Exception) {
            println("ERROR: captureWebViewUrls failed for $initialUrl (${e.message})")
            throw ParseException("Failed to capture webview URLs", initialUrl, e)
        }

        println("DEBUG: Captured ${capturedUrls.size} URLs from WebView")

        // Now get the HTML content from the loaded page
        val script = """
            document.documentElement ? document.documentElement.outerHTML : "";
        """.trimIndent()

        val html = try {
            context.evaluateJs(initialUrl, script)
        } catch (e: Exception) {
            println("ERROR: evaluateJs failed after captureWebViewUrls for $initialUrl (${e.message})")
            null
        }

        if (html.isNullOrBlank()) {
            println("ERROR: No HTML content captured for $initialUrl")
        } else {
            val doc = Jsoup.parse(html, initialUrl)
            println("DEBUG: Captured ${html.length} chars for $initialUrl")

            // Check for valid content
            if (hasValidMangaLivreContent(doc)) {
                println("DEBUG: Found valid MangaLivre content")
                return doc
            }

            // Check if it's a Cloudflare challenge
            if (!isActiveCloudflareChallenge(html) && html.length > 1000) {
                println("DEBUG: Accepting substantial non-Cloudflare content")
                return doc
            }

            println("WARN: Content appears to be Cloudflare challenge or invalid")
        }

        if (allowBrowserAction) {
            println("INFO: Requesting browser action to solve Cloudflare for $initialUrl")
            context.requestBrowserAction(this, initialUrl)
            throw ParseException("Browser action requested for Cloudflare bypass", initialUrl)
        }

        throw ParseException("Failed to load page content", initialUrl)
    }


    private fun hasValidMangaLivreContent(doc: Document): Boolean {
        // Check for MangaLivre-specific content that indicates successful load
        return doc.select("div.manga__item").isNotEmpty() ||
            doc.select("div.search-lists").isNotEmpty() ||
            doc.select("div.page-content-listing").isNotEmpty() ||
            doc.select("div.genres_wrap").isNotEmpty() ||
            doc.select("header ul.second-menu").isNotEmpty() ||
            doc.title().contains("manga livre", ignoreCase = true) ||
            doc.select("div.summary-content").isNotEmpty()
    }

    private fun isActiveCloudflareChallenge(html: String): Boolean {
        if (html.length < 100) {
            return true
        }
        val lower = html.lowercase()
        // Only reject pages that are clearly active challenge pages
        return (lower.contains("just a moment") && lower.contains("cloudflare")) ||
            (lower.contains("checking your browser") && lower.contains("cloudflare")) ||
            lower.contains("cf-browser-verification") ||
            lower.contains("cf-chl-opt")
    }

    // Override fetchAvailableTags to also use webview
    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        println("DEBUG: MangaLivre.fetchAvailableTags called")
        val url = "https://$domain/$listUrl"
        println("DEBUG: Loading tags URL via captureWebViewUrls: $url")
        val doc = captureDocument(url, buildPathPattern(listUrl))

        val body = doc.body()
        val root1 = body.selectFirst("header")?.selectFirst("ul.second-menu")
        val root2 = body.selectFirst("div.genres_wrap")?.selectFirst("ul.list-unstyled")
        if (root1 == null && root2 == null) {
            return emptySet()
        }
        val list = root1?.select("li").orEmpty() + root2?.select("li").orEmpty()
        val keySet = HashSet<String>(list.size)
        return list.mapNotNullToSet { li ->
            val a = li.selectFirst("a") ?: return@mapNotNullToSet null
            val href = a.attr("href").removeSuffix('/').substringAfterLast(tagPrefix, "")
            if (href.isEmpty() || !keySet.add(href)) {
                return@mapNotNullToSet null
            }
            MangaTag(
                key = href,
                title = a.ownText().ifEmpty {
                    a.selectFirst(".menu-image-title")?.textOrNull()
                }?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null,
                source = source,
            )
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        println("DEBUG: MangaLivre.getListPage called with page=$page")

        val pages = page + 1

        val url = buildString {
            append("https://")
            append(domain)

            if (pages > 1) {
                append("/page/")
                append(pages.toString())
            }
            append("/?s=")

            filter.query?.let {
                append(filter.query.urlEncoded())
            }

            append("&post_type=wp-manga")

            if (filter.tags.isNotEmpty()) {
                filter.tags.forEach {
                    append("&genre[]=")
                    append(it.key)
                }
            }

            filter.states.forEach {
                append("&status[]=")
                when (it) {
                    MangaState.ONGOING -> append("on-going")
                    MangaState.FINISHED -> append("end")
                    MangaState.ABANDONED -> append("canceled")
                    MangaState.PAUSED -> append("on-hold")
                    MangaState.UPCOMING -> append("upcoming")
                    else -> throw IllegalArgumentException("$it not supported")
                }
            }

            filter.contentRating.oneOrThrowIfMany()?.let {
                append("&adult=")
                append(
                    when (it) {
                        ContentRating.SAFE -> "0"
                        ContentRating.ADULT -> "1"
                        else -> ""
                    },
                )
            }

            if (filter.year != 0) {
                append("&release=")
                append(filter.year.toString())
            }

            append("&m_orderby=")

            when (order) {
                SortOrder.POPULARITY -> append("views")
                SortOrder.UPDATED -> append("latest")
                SortOrder.NEWEST -> append("new-manga")
                SortOrder.ALPHABETICAL -> append("alphabet")
                SortOrder.RATING -> append("rating")
                SortOrder.RELEVANCE -> {}
                else -> {}
            }
        }

        println("DEBUG: Loading list URL via captureWebViewUrls: $url")
        val doc = captureDocument(url, Regex("post_type=wp-manga"))
        return parseMangaList(doc)
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        val items = doc.select(".search-lists .manga__item, .page-content-listing .manga__item")
            .ifEmpty { doc.select(".manga__item") }

        if (items.isEmpty()) {
            return super.parseMangaList(doc)
        }

        return items.mapNotNull { item ->
            val link = item.selectFirst(".manga__thumb a[href], .post-title a[href], a[href]")
                ?: return@mapNotNull null
            val href = link.attrAsRelativeUrlOrNull("href") ?: link.attr("href")
            if (href.isBlank()) {
                return@mapNotNull null
            }

            val title = item.selectFirst(".post-title a")?.text()?.trim()?.takeUnless { it.isEmpty() }
                ?: link.attr("title").trim().takeUnless { it.isEmpty() }
                ?: link.text().trim().takeUnless { it.isEmpty() }

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = item.selectFirst("img")?.src(),
                title = title ?: href,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = item.select(".manga-genres a").mapNotNullToSet { a ->
                    val slug = a.attr("href").removeSuffix('/').substringAfterLast('/')
                    if (slug.isEmpty()) {
                        return@mapNotNullToSet null
                    }
                    val tagTitle = a.text().trim()
                    if (tagTitle.isEmpty()) {
                        return@mapNotNullToSet null
                    }
                    MangaTag(
                        key = slug,
                        title = tagTitle.toTitleCase(),
                        source = source,
                    )
                },
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val fullUrl = seed.url.toAbsoluteUrl(domain)
        val doc = captureDocument(fullUrl, buildPathPattern(seed.url))
        val root = doc.body().selectFirst(".related-manga") ?: return emptyList()
        return root.select("div.related-reading-wrap").mapNotNull { div ->
            val link = div.selectFirst("a") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(link.host ?: domain),
                altTitles = emptySet(),
                title = div.selectFirst(".widget-title")?.text().orEmpty().ifEmpty { return@mapNotNull null },
                authors = emptySet(),
                coverUrl = div.selectFirst("img")?.src(),
                tags = emptySet(),
                rating = RATING_UNKNOWN,
                state = null,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        println("DEBUG: MangaLivre.getDetails called for: ${manga.title}")

        val fullUrl = manga.url.toAbsoluteUrl(domain)

        // Use webview for Cloudflare bypass
        println("DEBUG: Loading details URL via captureWebViewUrls: $fullUrl")
        val doc = captureDocument(fullUrl, buildPathPattern(manga.url))

        val chapters = loadChapters(manga.url, doc)

        val desc = doc.select(selectDesc).html()
        val stateDiv = doc.selectFirst(selectState)?.selectLast("div.summary-content")
        val state = stateDiv?.let {
            when (it.text().lowercase()) {
                in ongoing -> MangaState.ONGOING
                in finished -> MangaState.FINISHED
                in abandoned -> MangaState.ABANDONED
                in paused -> MangaState.PAUSED
                else -> null
            }
        }
        val alt = doc.body().select(selectAlt).firstOrNull()?.tableValue()?.textOrNull()

        return manga.copy(
            title = doc.selectFirst("h1")?.textOrNull() ?: manga.title,
            tags = doc.body().select(selectGenre).mapToSet { a -> createMangaTag(a) }.filterNotNull().toSet(),
            description = desc,
            altTitles = setOfNotNull(alt),
            state = state,
            chapters = chapters,
            contentRating = if (doc.selectFirst(".adult-confirm") != null || isNsfwSource) {
                ContentRating.ADULT
            } else {
                ContentRating.SAFE
            }
        )
    }
}
