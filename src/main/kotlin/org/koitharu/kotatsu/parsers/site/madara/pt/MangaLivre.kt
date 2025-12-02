package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGALIVRE", "Manga Livre", "pt")
internal class MangaLivre(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGALIVRE, "mangalivre.tv") {
    override val datePattern = "MMMM dd, yyyy"
    override val withoutAjax = true
    override val stylePage = ""

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .apply {
            // Add CloudFlare clearance cookies for cover images
            val cookies = context.cookieJar.getCookies(domain)
            val cookieString = cookies.filter { cookie ->
                cookie.name == "cf_clearance" ||
                cookie.name.startsWith("__cf") ||
                cookie.name.startsWith("cf_")
            }.joinToString("; ") { "${it.name}=${it.value}" }

            if (cookieString.isNotEmpty()) {
                add("Cookie", cookieString)
            }
        }
        .build()

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
        println("DEBUG: captureDocument loading $initialUrl with evaluateJs (redirects enabled)")

        // Simple script: return content if ready, null to keep waiting
        val script = """
            (() => {
                // Conservative CloudFlare detection - only detect when absolutely sure it's blocking
                const hasBlockedTitle = document.title && document.title.toLowerCase().includes('access denied');
                const hasActiveChallengeForm = document.querySelector('form[action*="__cf_chl"]') !== null;
                const hasChallengeScript = document.querySelector('script[src*="challenge-platform"]') !== null;

                // Only return blocked if we're absolutely certain
                if (hasBlockedTitle || hasActiveChallengeForm || hasChallengeScript) {
                    return "CLOUDFLARE_BLOCKED";
                }

                // Check for REAL manga content (strict check for actual data)
                const hasRealMangaContent = document.querySelectorAll('.manga__item').length > 0 ||
                                          document.querySelectorAll('.search-lists').length > 0 ||
                                          document.querySelectorAll('.page-content-listing').length > 0 ||
                                          document.querySelectorAll('.genres_wrap').length > 0 ||
                                          document.querySelectorAll('header ul.second-menu').length > 0 ||
                                          document.querySelectorAll('.summary-content').length > 0;

                if (hasRealMangaContent) {
                    return document.documentElement ? document.documentElement.outerHTML : "";
                }

                // No challenge but no real content yet - return null to keep waiting
                return null;
            })();
        """.trimIndent()

        val html = try {
            context.evaluateJs(initialUrl, script, timeout = timeoutMs)?.let { raw ->
                // Remove surrounding quotes if present and decode escapes
                val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                    raw.substring(1, raw.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                } else raw

                // Decode Unicode escapes like \u003C to < more efficiently
                unquoted.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
                    val hexValue = match.groupValues[1]
                    hexValue.toInt(16).toChar().toString()
                }
            }
        } catch (e: Exception) {
            println("ERROR: evaluateJs failed for $initialUrl (${e.message})")
            null
        }

        if (html.isNullOrBlank()) {
            println("ERROR: No HTML content captured for $initialUrl")
        } else if (html == "CLOUDFLARE_BLOCKED") {
            println("INFO: Cloudflare challenge detected, requesting browser action")
        } else {
            val doc = Jsoup.parse(html, initialUrl)
            println("DEBUG: Captured ${html.length} chars for $initialUrl")

            // Debug what's actually in the HTML
            val title = doc.title()
            val bodyText = doc.body()?.text()?.take(200) ?: "no body"
            println("DEBUG: Page title: '$title'")
            println("DEBUG: Body preview: '$bodyText'")

            // Only return if we have valid manga content
            if (hasValidMangaLivreContent(doc)) {
                println("DEBUG: Found valid MangaLivre content")
                return doc
            } else {
                println("DEBUG: No valid MangaLivre content found - treating as blocked")
                // Don't return the document if it doesn't have manga content
            }
        }

        if (allowBrowserAction) {
            println("INFO: Requesting browser action to solve Cloudflare for $initialUrl")
            context.requestBrowserAction(this, initialUrl)
            throw ParseException("Browser action requested for Cloudflare bypass", initialUrl)
        }

        throw ParseException("Failed to load page content", initialUrl)
    }


    private fun hasValidMangaLivreContent(doc: Document): Boolean {
        // Conservative CloudFlare detection - only reject when absolutely sure it's blocking
        val hasBlockedTitle = doc.title().lowercase().contains("access denied")
        val hasActiveChallengeForm = doc.selectFirst("form[action*=__cf_chl]") != null
        val hasChallengeScript = doc.selectFirst("script[src*=challenge-platform]") != null

        // Only reject if we're absolutely certain it's blocked
        if (hasBlockedTitle || hasActiveChallengeForm || hasChallengeScript) {
            return false
        }

        // Check for MangaLivre-specific content OR basic page indicators
        val hasMangaContent = doc.select("div.manga__item").isNotEmpty() ||
            doc.select("div.search-lists").isNotEmpty() ||
            doc.select("div.page-content-listing").isNotEmpty() ||
            doc.select("div.genres_wrap").isNotEmpty() ||
            doc.select("header ul.second-menu").isNotEmpty() ||
            doc.select("div.summary-content").isNotEmpty()

        // Also accept if it's a working MangaLivre page (less strict)
        val isMangaLivrePage = doc.title().contains("manga livre", ignoreCase = true) ||
            (doc.body()?.html()?.length ?: 0) > 5000

        return hasMangaContent || isMangaLivrePage
    }

    private fun isActiveCloudflareChallenge(html: String): Boolean {
        if (html.length < 100) {
            return true
        }

        // Parse HTML to check for CloudFlare elements (mimicking CloudFlareHelper logic)
        val doc = Jsoup.parse(html)

        // Check for blocked page indicators
        val isBlocked = doc.selectFirst("h2[data-translate=\"blocked_why_headline\"]") != null

        // Check for captcha challenge indicators
        val isCaptchaChallenge = doc.getElementById("challenge-error-title") != null ||
            doc.getElementById("challenge-error-text") != null ||
            doc.selectFirst(".cf-browser-verification") != null

        val lower = html.lowercase()
        // Check for text-based challenge indicators
        val isTextChallenge = lower.contains("just a moment") ||
            lower.contains("checking your browser") ||
            lower.contains("please wait") ||
            lower.contains("un momento") ||
            lower.contains("un instant") ||
            lower.contains("nous vérifions") ||
            lower.contains("vérification") ||
            lower.contains("cf-chl-opt")

        return isBlocked || isCaptchaChallenge || isTextChallenge
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
            val title = doc.title()
            val bodySnippet = body.outerHtml().take(800)
            throw ParseException(
                "Could not find tag containers in MangaLivre page. " +
                    "Page title: '$title'. " +
                    "Expected 'header ul.second-menu' or 'div.genres_wrap ul.list-unstyled'. " +
                    "Body HTML snippet: '$bodySnippet'",
                url
            )
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

            // Don't specify m_orderby - MangaLivre now blocks special sort parameters
            // Only use relevance (default) for all requests
        }

        println("DEBUG: Loading list URL via captureWebViewUrls: $url")
        val doc = captureDocument(url, Regex("post_type=wp-manga"))
        return parseMangaList(doc)
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        // Try multiple selector combinations
        var items = doc.select(".search-lists .manga__item")
        println("DEBUG: Found ${items.size} items with .search-lists .manga__item")

        if (items.isEmpty()) {
            items = doc.select(".page-content-listing .manga__item")
            println("DEBUG: Found ${items.size} items with .page-content-listing .manga__item")
        }

        if (items.isEmpty()) {
            items = doc.select("div.manga__item")
            println("DEBUG: Found ${items.size} items with div.manga__item")
        }

        if (items.isEmpty()) {
            // Debug what containers exist
            val searchLists = doc.select(".search-lists")
            val pageContent = doc.select(".page-content-listing")
            val allMangaItems = doc.select(".manga__item")
            println("DEBUG: Found ${searchLists.size} .search-lists containers")
            println("DEBUG: Found ${pageContent.size} .page-content-listing containers")
            println("DEBUG: Found ${allMangaItems.size} total .manga__item elements")

            if (allMangaItems.isNotEmpty()) {
                items = allMangaItems
                println("DEBUG: Using all .manga__item elements")
            }
        }

        println("DEBUG: Final count: ${items.size} manga items")
        if (items.isEmpty()) {
            println("DEBUG: No items found, throwing ParseException with page info")
            val title = doc.title()
            val bodyText = doc.body()?.text()?.take(500) ?: "no body content"
            val htmlSnippet = doc.outerHtml().take(1000)

            throw ParseException(
                "No manga items found in parsed HTML. " +
                    "Page title: '$title'. " +
                    "Body preview: '$bodyText'. " +
                    "HTML snippet: '$htmlSnippet'",
                doc.location()
            )
        }

        val results = items.mapNotNull { item ->
            val link = item.selectFirst(".manga__thumb_item a[href], .manga__thumb a[href], .post-title a[href], a[href]")
            if (link == null) {
                println("DEBUG: No link found in item: ${item.html().take(200)}")
                return@mapNotNull null
            }
            val href = link.attrAsRelativeUrlOrNull("href") ?: link.attr("href")
            if (href.isBlank()) {
                return@mapNotNull null
            }

            val title = item.selectFirst(".post-title h2 a, .post-title a")?.text()?.trim()?.takeUnless { it.isEmpty() }
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
                tags = item.select(".manga-genres a, span.manga-genres a").mapNotNullToSet { a ->
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

        println("DEBUG: Parsed ${results.size} manga from ${items.size} items")

        if (results.isEmpty() && items.isNotEmpty()) {
            // We found items but couldn't parse any valid manga from them
            val sampleItem = items.firstOrNull()
            val sampleHtml = sampleItem?.outerHtml()?.take(500) ?: "no sample item"

            throw ParseException(
                "Found ${items.size} manga items but failed to parse any valid manga. " +
                    "Sample item HTML: '$sampleHtml'",
                doc.location()
            )
        }

        return results
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

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // Set the manga reading pass cookie for chapter access
        context.cookieJar.insertCookies(domain, "manga_reading_ml=verified")

        // Get all cookies including CloudFlare and reading pass cookies
        val cookies = context.cookieJar.getCookies(domain)
        val cookieString = cookies.joinToString("; ") { "${it.name}=${it.value}" }

        val headers = getRequestHeaders().newBuilder()
            .apply {
                if (cookieString.isNotEmpty()) {
                    set("Cookie", cookieString)
                }
            }
            .build()

        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl, headers).parseHtml()

        if (doc.selectFirst(selectRequiredLogin) != null) {
            throw AuthRequiredException(source)
        }

        val root = doc.body().selectFirst(selectBodyPage) ?: throw ParseException(
            "No image found, try to log in",
            fullUrl,
        )

        return root.select(selectPage).flatMap { div ->
            div.selectOrThrow("img").map { img ->
                val url = img.requireSrc().toRelativeUrl(domain)
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }
    }
}
