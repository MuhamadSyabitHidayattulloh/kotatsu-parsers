package org.koitharu.kotatsu.parsers.site.madara.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.Locale

@MangaSourceParser("MADARADEX", "MadaraDex", "en", ContentType.HENTAI)
internal class MadaraDex(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MADARADEX, "madaradex.org") {

    init {
        context.cookieJar.insertCookies(domain, "wpmanga-adault=1")
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.remove(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("User-Agent", UserAgents.CHROME_DESKTOP)
        .build()

    override val authUrl: String
        get() = "https://${domain}"

    override suspend fun isAuthorized(): Boolean {
        return context.cookieJar.getCookies(domain).any {
            it.name.contains("cm_uaid")
        }
    }

    override val listUrl = "title/"
    override val tagPrefix = "genre/"
    override val postReq = true

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)

        // Warm up Cloudflare by driving a lightweight WebView visit before fetching images.
        runCatching {
            context.captureWebViewUrls(
                pageUrl = fullUrl,
                urlPattern = Regex("https?://cdn\\.madaradex\\.org/.*", RegexOption.IGNORE_CASE),
                timeout = 4000L,
            )
        }.getOrNull()

        val doc = loadChapterDocument(fullUrl)
        val root = doc.body().selectFirst(selectBodyPage)
            ?: throw ParseException("No image found, try to log in", fullUrl)

        return root.select(selectPage).flatMap { div ->
            div.selectOrThrow("img").map { img ->
                val rawUrl = img.requireSrc().toRelativeUrl(domain)
                val cleanUrl = rawUrl.substringBefore('#')
                MangaPage(
                    id = generateUid(cleanUrl),
                    url = cleanUrl,
                    preview = null,
                    source = source,
                )
            }
        }
    }

    private suspend fun loadChapterDocument(url: String): Document {
        var doc = fetchChapterDocument(url)
        if (doc != null && !isCloudflareDocument(doc)) {
            return doc
        }

        context.requestBrowserAction(this, url)

        runCatching {
            context.captureWebViewUrls(
                pageUrl = url,
                urlPattern = Regex("https?://cdn\\.madaradex\\.org/.*", RegexOption.IGNORE_CASE),
                timeout = 6000L,
            )
        }.getOrNull()

        doc = fetchChapterDocument(url)
        if (doc == null || isCloudflareDocument(doc)) {
            throw ParseException(
                "Cloudflare verification is still required. Please open the chapter in the in-app browser and retry.",
                url,
            )
        }
        return doc
    }

    private suspend fun fetchChapterDocument(url: String): Document? {
        val response = runCatching { webClient.httpGet(url) }.getOrElse { return null }
        return response.use { res -> runCatching { res.parseHtml() }.getOrNull() }
    }

    private fun isCloudflareDocument(doc: Document): Boolean {
        val html = doc.outerHtml()
        if (html.length < 200) {
            return true
        }
        val lower = html.lowercase(Locale.ROOT)
        return lower.contains("cf-browser-verification") ||
            lower.contains("turnstile") ||
            lower.contains("checking your browser") ||
            lower.contains("checking if the site connection is secure") ||
            lower.contains("challenge-platform")
    }
}
