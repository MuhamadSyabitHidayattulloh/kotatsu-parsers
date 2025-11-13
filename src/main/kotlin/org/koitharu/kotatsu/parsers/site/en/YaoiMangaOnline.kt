package org.koitharu.kotatsu.parsers.site.en

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.HashSet
import java.util.Locale

@MangaSourceParser("YAOIMANGAONLINE", "YaoiMangaOnline", "en", ContentType.HENTAI)
internal class YaoiMangaOnline(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.YAOIMANGAONLINE, 12) {

	override val configKeyDomain = ConfigKey.Domain("yaoimangaonline.com")

	private val listPath = "yaoi-manga"

	private val detailDateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)

	private val chapterNumberRegex = Regex("""(\d+(?:\.\d+)?)""")

	private val cloudflareMessage = "Cloudflare verification is required. Open the source in the in-app browser, complete the check, then try again."

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.RELEVANCE)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	private val availableTags = suspendLazy(soft = true) { fetchTags() }

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
	)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val isSearch = !filter.query.isNullOrEmpty()
		val tagFilter = if (isSearch) {
			null
		} else {
			filter.tags.firstOrNull { tag ->
				tag.source == source && tag.key != tag.title
			}
		}
		val fullUrl = if (isSearch) {
			buildString {
				append("https://")
				append(domain)
				append("/?s=")
				append(filter.query!!.urlEncoded())
				if (page > 1) {
					append("&paged=")
					append(page)
				}
			}
		} else if (tagFilter != null) {
			buildString {
				append("https://")
				append(domain)
				append("/tag/")
				append(tagFilter.key)
				append('/')
				if (page > 1) {
					append("page/")
					append(page)
					append('/')
				}
			}
		} else {
			buildString {
				append("https://")
				append(domain)
				append('/')
				append(listPath)
				if (page > 1) {
					append("/page/")
					append(page)
					append('/')
				}
			}
		}

		val document = fetchDocument(fullUrl, preferWebView = isSearch)
		return document.select("article").mapNotNull { article ->
			val titleAnchor = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
			val rawHref = titleAnchor.attrAsRelativeUrlOrNull("href")
				?: titleAnchor.attr("href").toRelativeUrl(domain)
			val coverUrl = article.selectFirst("img")?.let { img ->
				img.resolveImageUrl()
			}

			Manga(
				id = generateUid(rawHref),
				url = rawHref,
				publicUrl = rawHref.toAbsoluteUrl(domain),
				title = titleAnchor.text().trim(),
				altTitles = emptySet(),
				coverUrl = coverUrl,
				largeCoverUrl = null,
				description = null,
				authors = emptySet(),
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				source = source,
				contentRating = ContentRating.ADULT,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val detailUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDocument(detailUrl)
		val description = doc.select("div.entry-content > p").joinToString(separator = "\n\n") { it.text() }
			.nullIfEmpty()
		val tagSet = doc.select("a[rel='tag'], a[rel='category tag']").mapNotNullToSet { anchor ->
			val text = anchor.text().nullIfEmpty() ?: return@mapNotNullToSet null
			val slug = anchor.attrOrNull("href")
				?.trimEnd('/')
				?.substringAfterLast('/')
				?.nullIfEmpty()
				?: text.lowercase(Locale.US).replace(' ', '-')
			MangaTag(
				key = slug,
				title = text.toTitleCase(sourceLocale),
				source = source,
			)
		}

		val chapterElements = doc.select("nav.mpp-toc ul li a")
		val chapters = if (chapterElements.isNotEmpty()) {
			data class ChapterCandidate(
				val index: Int,
				val href: String,
				val title: String,
				val number: Float?,
			)

			val candidates = chapterElements.mapIndexedNotNull { index, anchor ->
				val href = anchor.attrAsRelativeUrlOrNull("href")
					?: anchor.attr("href").toRelativeUrl(domain)
				val rawTitle = anchor.text().nullIfEmpty() ?: anchor.attrOrNull("title")?.nullIfEmpty()
					?: "Chapter ${index + 1}"
				val number = chapterNumberRegex.find(rawTitle)?.value?.toFloatOrNull()
				ChapterCandidate(
					index = index,
					href = href,
					title = rawTitle,
					number = number,
				)
			}
			val sorted = candidates.sortedWith(
				compareBy<ChapterCandidate> { it.number ?: (it.index + 1).toFloat() }
					.thenBy { it.index },
			)
			sorted.mapIndexed { mappedIndex, candidate ->
				MangaChapter(
					id = generateUid(candidate.href),
					url = candidate.href,
					title = candidate.title,
					number = candidate.number ?: (mappedIndex + 1).toFloat(),
					uploadDate = 0L,
					volume = 0,
					branch = null,
					scanlator = null,
					source = source,
				)
			}
		} else {
			val uploadDate = doc.selectFirst("div.entry-meta span.updated")?.text()
				?.let { detailDateFormat.parseSafe(it) }
				?: 0L
			listOf(
				MangaChapter(
					id = manga.id,
					url = manga.url,
					title = "Oneshot",
					number = 1f,
					uploadDate = uploadDate,
					volume = 0,
					branch = null,
					scanlator = null,
					source = source,
				),
			)
		}

		return manga.copy(
			description = description,
			tags = if (tagSet.isEmpty()) manga.tags else tagSet,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterDoc = fetchDocument(chapter.url.toAbsoluteUrl(domain))
		val contentRoot = chapterDoc.selectFirst("div.entry-content") ?: chapterDoc.body()
		val seen = HashSet<String>()
		return contentRoot.select("img").mapNotNull { img ->
			val imageUrl = img.resolveImageUrl()
				?.takeUnless { it.startsWith("data:") }
				?.takeUnless { !it.contains("/wp-content/") }
				?: return@mapNotNull null
			if (!seen.add(imageUrl)) {
				return@mapNotNull null
			}
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchDocument(url: String, preferWebView: Boolean = false): Document {
		if (preferWebView) {
			loadDocumentViaWebView(url)?.let { doc ->
				if (!doc.isCloudflareChallenge()) {
					return doc
				}
			}
			context.requestBrowserAction(this, url)
			loadDocumentViaWebView(url)?.let { doc ->
				if (!doc.isCloudflareChallenge()) {
					return doc
				}
			}
			throw ParseException(cloudflareMessage, url)
		}

		fetchHttpDocument(url)?.let { doc ->
			if (!doc.isCloudflareChallenge()) {
				return doc
			}
		}
		context.requestBrowserAction(this, url)
		fetchHttpDocument(url)?.let { doc ->
			if (!doc.isCloudflareChallenge()) {
				return doc
			}
		}
		throw ParseException(cloudflareMessage, url)
	}

	private suspend fun fetchHttpDocument(url: String): Document? {
		val response = runCatching { webClient.httpGet(url) }.getOrElse { return null }
		return response.use { res -> runCatching { res.parseHtml() }.getOrNull() }
	}

	private suspend fun loadDocumentViaWebView(url: String): Document? {
		val script = """
			(() => {
				const hasContent = () => {
					if (!document.documentElement) {
						return false;
					}
					const html = document.documentElement.outerHTML.toLowerCase();
					if (html.includes("cf-browser-verification") || html.includes("verify you are human") || html.includes("just a moment")) {
						return true;
					}
					return document.querySelectorAll("article").length > 0 || document.querySelector("nav.mpp-toc") !== null;
				};
				return new Promise(resolve => {
					const finish = () => {
						resolve(document.documentElement ? document.documentElement.outerHTML : "");
					};
					const waitForContent = (start) => {
						if (hasContent() || Date.now() - start > 3500) {
							finish();
						} else {
							setTimeout(() => waitForContent(start), 150);
						}
					};
					if (document.readyState === "complete") {
						waitForContent(Date.now());
					} else {
						window.addEventListener("load", () => waitForContent(Date.now()), { once: true });
						setTimeout(() => waitForContent(Date.now()), 2000);
					}
					setTimeout(finish, 5000);
				});
			})();
		""".trimIndent()

		val html = context.evaluateJs(url, script) ?: return null
		if (html.isBlank()) {
			return null
		}
		return Jsoup.parse(html, url)
	}

	private fun Document.isCloudflareChallenge(): Boolean {
		val title = title().lowercase(Locale.US)
		if (title.contains("just a moment") || title.contains("attention required")) {
			return true
		}
		val bodyHtml = body().html().lowercase(Locale.US)
		return bodyHtml.contains("cf-browser-verification") ||
			bodyHtml.contains("checking if the site connection is secure") ||
			bodyHtml.contains("cf-chl") ||
			bodyHtml.contains("cf-turnstile") ||
			bodyHtml.contains("verify you are human") ||
			bodyHtml.contains("cloudflare");
	}

	private fun Element.resolveImageUrl(): String? {
		return attrAsAbsoluteUrlOrNull("data-src")
			?: attrAsAbsoluteUrlOrNull("data-lazy-src")
			?: attrAsAbsoluteUrlOrNull("src")
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		return doc.select("div.tagcloud a").mapNotNullToSet { anchor ->
			val text = anchor.text().nullIfEmpty() ?: return@mapNotNullToSet null
			val href = anchor.attrOrNull("href")?.trimEnd('/') ?: return@mapNotNullToSet null
			val slug = href.substringAfterLast('/')
			MangaTag(
				key = slug,
				title = text.toTitleCase(sourceLocale),
				source = source,
			)
		}
	}
}
