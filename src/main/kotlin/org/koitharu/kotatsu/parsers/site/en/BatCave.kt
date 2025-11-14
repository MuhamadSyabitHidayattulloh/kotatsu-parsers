package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("BATCAVE", "BatCave", "en", ContentType.COMICS)
internal class BatCave(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.BATCAVE, 20) {

	override val configKeyDomain = ConfigKey.Domain("batcave.biz")

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .build()

	private val availableTags = suspendLazy(initializer = ::fetchTags)
	private val captureAllPattern = Regex(".*")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isYearRangeSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
	)

	private suspend fun captureDocument(
		initialUrl: String,
		preferredMatch: Regex? = null,
		timeoutMs: Long = 15000L,
		allowBrowserAction: Boolean = true,
	): Document {
		tryHttpDocument(initialUrl)?.let { doc ->
			return doc
		}

		val capturedUrls = try {
			context.captureWebViewUrls(
				pageUrl = initialUrl,
				urlPattern = captureAllPattern,
				timeout = timeoutMs,
			)
		} catch (e: Exception) {
			throw ParseException("Failed to capture webview URLs", initialUrl, e)
		}

		if (capturedUrls.isEmpty()) {
			throw ParseException("WebView did not produce any matching requests", initialUrl)
		}

		val resolvedUrl = preferredMatch?.let { pattern ->
			capturedUrls.firstOrNull { pattern.containsMatchIn(it) }
		} ?: capturedUrls.firstOrNull { url ->
			url.startsWith("https://$domain") || url.startsWith("http://$domain")
		} ?: capturedUrls.firstOrNull()

		val finalUrl = resolvedUrl ?: initialUrl

		tryHttpDocument(finalUrl)?.let { doc ->
			return doc
		}

		loadDocumentViaWebView(finalUrl)?.let { doc ->
			return doc
		}

		if (allowBrowserAction) {
			context.requestBrowserAction(this, finalUrl)
			return captureDocument(initialUrl, preferredMatch, timeoutMs, allowBrowserAction = false)
		}

		throw ParseException("Failed to load page via webview", finalUrl)
	}

	private suspend fun tryHttpDocument(url: String): Document? {
		val response = runCatching { webClient.httpGet(url) }.getOrNull() ?: return null
		response.use { res ->
			val protection = CloudFlareHelper.checkResponseForProtection(res.copy())
			if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
				return null
			}
			val doc = res.parseHtml()
			val html = doc.outerHtml()
			if (isCloudflareHtml(html)) {
				return null
			}
			return doc
		}
	}

	private suspend fun loadDocumentViaWebView(url: String): Document? {
		val script = """
			(() => {
				return new Promise(resolve => {
					const finish = () => {
						resolve(document.documentElement ? document.documentElement.outerHTML : "");
					};
					if (document.readyState === "complete") {
						setTimeout(finish, 200);
					} else {
						window.addEventListener("load", () => setTimeout(finish, 200), { once: true });
					}
					setTimeout(finish, 3000);
				});
			})();
		""".trimIndent()

		val html = context.evaluateJs(url, script) ?: return null
		if (html.isBlank()) {
			return null
		}
		if (isCloudflareHtml(html)) {
			return null
		}
		val doc = Jsoup.parse(html, url)
		return doc
	}

	private fun isCloudflareHtml(html: String): Boolean {
		if (html.length < 200) {
			return true
		}
		val lower = html.lowercase()
		return lower.contains("cf-browser-verification") ||
			lower.contains("checking if the site connection is secure") ||
			lower.contains("checking your browser before accessing") ||
			lower.contains("cf-chl") ||
			lower.contains("cf-turnstile") ||
			(lower.contains("cloudflare") && lower.contains("captcha"))
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val urlBuilder = StringBuilder()
		when {
			!filter.query.isNullOrEmpty() -> {
				val encodedQuery = filter.query.splitByWhitespace().joinToString(separator = "%20") { part ->
					part.urlEncoded()
				}
				urlBuilder.append("/search/")
				urlBuilder.append(encodedQuery)
				if (page > 1) {
					urlBuilder.append("/page/$page/")
				}
			}

			else -> {
				urlBuilder.append("/ComicList")
				if (filter.yearFrom != YEAR_UNKNOWN) {
					urlBuilder.append("/y[from]=${filter.yearFrom}")
				}
				if (filter.yearTo != YEAR_UNKNOWN) {
					urlBuilder.append("/y[to]=${filter.yearTo}")
				}
				if (filter.tags.isNotEmpty()) {
					urlBuilder.append("/g=")
					urlBuilder.append(filter.tags.joinToString(",") { it.key })
				}
				urlBuilder.append("/sort")
				if (page > 1) {
					urlBuilder.append("/page/$page/")
				}
			}
		}

		val fullUrl = urlBuilder.toString().toAbsoluteUrl(domain)
		val doc = captureDocument(fullUrl)
		return doc.select("div.readed.d-flex.short").map { item ->
			val a = item.selectFirstOrThrow("a.readed__img.img-fit-cover.anim")
			val titleElement = item.selectFirstOrThrow("h2.readed__title a")
			val img = item.selectFirst("img[data-src]")
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = a.attr("href"),
				title = titleElement.text(),
				altTitles = emptySet(),
				authors = emptySet(),
				description = null,
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				coverUrl = img?.attrAsAbsoluteUrlOrNull("data-src"),
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = captureDocument(manga.url.toAbsoluteUrl(domain))

		val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)

		val scriptData = doc.selectFirst("script:containsData(__DATA__)")?.data()
			?.let { data ->
				val jsonStart = data.indexOf("window.__DATA__ = ") + "window.__DATA__ = ".length
				val jsonEnd = data.indexOf("};", startIndex = jsonStart)
				if (jsonEnd != -1) {
					// substring, include "}" symbol
					data.substring(jsonStart, jsonEnd + 1)
				} else {
					null
				}
			} ?: doc.parseFailed("Script data not found")

		val jsonData = JSONObject(scriptData)
		val newsId = jsonData.getLong("news_id")
		val chaptersJson = jsonData.getJSONArray("chapters")

		val chapters = List(chaptersJson.length()) { i ->
			val chapter = chaptersJson.getJSONObject(i)
			val chapterId = chapter.getLong("id")

			MangaChapter(
				id = generateUid("$newsId/$chapterId"),
				url = "/reader/$newsId/$chapterId",
				number = chapter.getFloatOrDefault("posi", 0f),
				title = chapter.getStringOrNull("title"),
				uploadDate = dateFormat.parseSafe(chapter.getStringOrNull("date")),
				source = source,
				scanlator = null,
				branch = null,
				volume = 0,
			)
		}.reversed()

		val author = doc.selectFirst("li:contains(Publisher:)")
			?.textOrNull()
			?.substringAfter("Publisher:")
			?.trim()
			?.nullIfEmpty()
		val state = when (
			doc.selectFirst("li:contains(Release type:)")?.text()?.substringAfter("Release type:")?.trim()
		) {
			"Ongoing" -> MangaState.ONGOING
			else -> MangaState.FINISHED
		}

		val tagLinks = doc.getElementsByAttributeValueContaining("href", "/genres/")
		val tags = if (tagLinks.isNotEmpty()) {
			availableTags.getOrNull()?.let { allTags ->
				tagLinks.mapNotNullToSet { a ->
					val tagName = a.text()
					allTags.find { it.title.equals(tagName, ignoreCase = true) }
				}
			}
		} else {
			null
		}

		return manga.copy(
			authors = setOfNotNull(author),
			state = state,
			chapters = chapters,
			description = doc.select("div.page__text.full-text.clearfix").textOrNull(),
			tags = tags ?: manga.tags,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = captureDocument(chapter.url.toAbsoluteUrl(domain))
		val data = doc.selectFirst("script:containsData(__DATA__)")?.data()
			?.substringAfter("=")
			?.trim()
			?.removeSuffix(";")
			?.substringAfter("\"images\":[")
			?.substringBefore("]")
			?.split(",")
			?.map { it.trim().removeSurrounding("\"").replace("\\", "") }
			?: throw ParseException("Image data not found", chapter.url)

		return data.map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = captureDocument("https://$domain/comix/")
		val scriptData = doc.selectFirstOrThrow("script:containsData(__XFILTER__)").data()

		val genresJson = scriptData
			.substringAfter("\"g\":{")
			.substringBefore("}}}") + "}"

		val genresObj = JSONObject("{$genresJson}")
		val valuesArray = genresObj.getJSONArray("values")

		return Set(valuesArray.length()) { i ->
			val genre = valuesArray.getJSONObject(i)
			MangaTag(
				key = genre.getInt("id").toString(),
				title = genre.getString("value").toTitleCase(sourceLocale),
				source = source,
			)
		}
	}
}
