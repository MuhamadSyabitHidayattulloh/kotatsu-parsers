package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest

@MangaSourceParser("MANGALIVRE", "Manga Livre", "pt")
internal class MangaLivre(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGALIVRE, "mangalivre.tv") {
	override val datePattern = "MMMM dd, yyyy"
	override val withoutAjax = true

	// Override fetchAvailableTags to also use webview
	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		println("DEBUG: MangaLivre.fetchAvailableTags called")
		val url = "https://$domain/$listUrl"
		println("DEBUG: Loading tags URL via webview: $url")
		val html = context.evaluateJs(url, "document.documentElement.outerHTML")
		if (html == null) {
			println("ERROR: Webview returned null HTML for tags")
			throw ParseException("Failed to load tags via webview", url)
		}
		println("DEBUG: Webview returned tags HTML (length=${html.length})")
		val doc = Jsoup.parse(html, url)

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

        println("DEBUG: Loading URL via webview: $url")
        val requests = context.interceptWebViewRequests(
            url = url,
            interceptorScript = "return url.includes('post_type=wp-manga')",
            timeout = 15000L // 30 seconds
        )

        if (requests.isNotEmpty()) {
            val mainDocumentRequest = requests.first()
            val html = mainDocumentRequest.body?.toString()

            if (html.isNullOrBlank()) {
                println("ERROR: Intercepted request for ${mainDocumentRequest.url} but the body was empty.")
                throw ParseException("Intercepted request for main document had an empty body", url)
            }

            println("DEBUG: Successfully intercepted main document: ${mainDocumentRequest.url} (length=${html.length})")
            val doc = Jsoup.parse(html, url)
            return parseMangaList(doc)
        } else {
            println("ERROR: No request matching the interceptor script was captured.")
            throw ParseException("Failed to intercept the main webview request with the script.", url)

        }
    }

	override suspend fun getDetails(manga: Manga): Manga {
		println("DEBUG: MangaLivre.getDetails called for: ${manga.title}")

		val fullUrl = manga.url.toAbsoluteUrl(domain)

		// Use webview for Cloudflare bypass
		println("DEBUG: Loading details URL via webview: $fullUrl")
		val html = context.evaluateJs(fullUrl, "document.documentElement.outerHTML")
		if (html == null) {
			println("ERROR: Webview returned null HTML for details")
			throw ParseException("Failed to load manga details via webview", fullUrl)
		}
		println("DEBUG: Webview returned details HTML (length=${html.length})")
		val doc = Jsoup.parse(html, fullUrl)

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
			},
		)
	}
}
