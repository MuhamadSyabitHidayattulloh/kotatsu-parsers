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
    override val stylePage = ""

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)

        // Warm up Cloudflare by loading the chapter once in a WebView before fetching via HTTP.
        warmUpWithWebView(fullUrl, timeout = 4000L)

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
        if (doc?.hasChapterImages() == true) {
            return doc
        }

        warmUpWithWebView(url, timeout = 4000L)

        doc = fetchChapterDocument(url)
        if (doc?.hasChapterImages() == true) {
            return doc
        }

        context.requestBrowserAction(this, url)

        warmUpWithWebView(url, timeout = 6000L)

        doc = fetchChapterDocument(url)
        if (doc?.hasChapterImages() == true) {
            return doc
        }

        throw ParseException(
            "Cloudflare verification is still required. Please open the chapter in the in-app browser and retry.",
            url,
        )
    }

    private suspend fun fetchChapterDocument(url: String): Document? {
        val response = runCatching { webClient.httpGet(url) }.getOrElse { return null }
        return response.use { res -> runCatching { res.parseHtml() }.getOrNull() }
    }

    private suspend fun warmUpWithWebView(url: String, timeout: Long) {
        runCatching {
            val script = """
                (() => {
                    return new Promise(resolve => {
                        const finish = () => resolve('done');
                        if (document.readyState === 'complete') {
                            setTimeout(finish, 200);
                        } else {
                            window.addEventListener('load', () => setTimeout(finish, 200), { once: true });
                        }
                        setTimeout(finish, $timeout);
                    });
                })();
            """.trimIndent()

            context.evaluateJs(url, script)
        }.getOrNull()
    }

    private fun Document.hasChapterImages(): Boolean {
        val container = body().selectFirst(selectBodyPage) ?: return false
        return container.select(selectPage).any { page ->
            page.select("img").any { img ->
                img.hasAttr("src") ||
                    img.hasAttr("data-src") ||
                    img.hasAttr("data-lazy-src") ||
                    img.hasAttr("data-original")
            }
        }
    }

}
