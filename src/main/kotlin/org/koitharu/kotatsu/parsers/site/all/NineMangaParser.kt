package org.koitharu.kotatsu.parsers.site.all

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.*

internal abstract class NineMangaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	defaultDomain: String,
) : PagedMangaParser(context, source, pageSize = 26) {

	override val configKeyDomain = ConfigKey.Domain(defaultDomain)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	init {
		context.cookieJar.insertCookies(domain, "ninemanga_template_desk=no")
	}



	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Accept-Language", "fr-FR,fr;q=0.9")
		.build()

	override val availableSortOrders: Set<SortOrder> = Collections.singleton(
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchWithFiltersSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getOrCreateTagMap().values.toSet(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
		),
	)



	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			if (filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() || filter.states.isNotEmpty() || !filter.query.isNullOrEmpty()) {
				append("/search/")
				append("?page=")
				append(page.toString())

				append("&name_sel=contain&wd=")
				append(filter.query?.urlEncoded().orEmpty())
				append("&author_sel=contain&author=&artist_sel=contain&artist=")

				append("&category_id=")
				append(filter.tags.joinToString(separator = ",") { it.key })

				append("&out_category_id=")
				append(filter.tagsExclude.joinToString(separator = ",") { it.key })

				append("&completed_series=")
				filter.states.oneOrThrowIfMany()?.let {
					when (it) {
						MangaState.ONGOING -> append("no")
						MangaState.FINISHED -> append("yes")
						else -> append("either")
					}
				} ?: append("either")
				
				append("&released=0&type=high")

			} else {
				append("/list/Hot-Book/")
				if (page > 1) {
					append("?page=")
					append(page.toString())
				}
			}
		}
		val doc = captureDocument(url)
		val root = doc.body().selectFirstOrThrow("ul#list_container")
		val baseHost = root.baseUri().toHttpUrl().host
		return root.select("li").map { node ->
			val href = node.selectFirstOrThrow("dt > a").attrAsAbsoluteUrl("href")
			val relUrl = href.toRelativeUrl(baseHost)
			val dd = node.selectFirst("dd.book-list")
			Manga(
				id = generateUid(relUrl),
				url = relUrl,
				publicUrl = href,
				title = dd?.selectFirst("a > b")?.text()?.toCamelCase().orEmpty(),
				altTitles = emptySet(),
				coverUrl = node.selectFirst("img")?.src(),
				rating = RATING_UNKNOWN,
				authors = emptySet(),
				contentRating = null,
				tags = dd?.select("span")?.flatMap { span -> 
					span.text().split(",").mapNotNull { tag ->
						tag.trim().takeIf { it.isNotEmpty() }?.let { MangaTag(key = it, title = it, source = source) }
					} 
				}?.toSet().orEmpty(),
				state = null,
				source = source,
				description = null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = captureDocument(
			manga.url.toAbsoluteUrl(domain) + "?waring=1",
		)
		val root = doc.body().selectFirstOrThrow("div.book-left")
		val infoRoot = root.selectFirstOrThrow("div.book-info")
		
		val author = infoRoot.select("dd.about-book > p").find { it.text().contains("Autor:", ignoreCase = true) }?.select("a")?.text()
		val statusText = infoRoot.select("dd.about-book > p").find { it.text().contains("Status:", ignoreCase = true) }?.select("a")?.text()
		val description = infoRoot.selectFirst("dd.short-info p span")?.text()?.trim()
		
		return manga.copy(
			title = infoRoot.selectFirst("h1")?.textOrNull()?.removeSuffix("Manga")?.trimEnd() ?: manga.title,
			tags = root.select("ul.inset-menu > li > a").mapNotNullToSet { 
				val text = it.text()
				if (text.isNotEmpty()) MangaTag(key = text, title = text, source = source) else null 
			},
			authors = setOfNotNull(author),
			state = statusText?.let { parseStatus(it) },
			description = description,
			chapters = root.selectFirst("ul.chapter-box")?.select("li")?.let { chapterElements ->
				val chapters = mutableListOf<MangaChapter>()
				for (i in chapterElements.indices) {
					val li = chapterElements[i]
					try {
						// Try both selectors without throwing
						val a = li.selectFirst("div.chapter-name.long > a") ?: li.selectFirst("div.chapter-name.short > a")
						if (a != null) {
							val href = a.attr("href").let { url ->
								if (url.startsWith("http")) url.substringAfter(domain) else url
							}.replace("%20", " ")

							chapters.add(MangaChapter(
								id = generateUid(href),
								title = a.text().takeIf { it.isNotEmpty() },
								number = (i + 1).toFloat(),
								volume = 0,
								url = href,
								uploadDate = parseChapterDateByLang(li.selectFirst("div.add-time > span")?.text().orEmpty()),
								source = source,
								scanlator = null,
								branch = null,
							))
						}
					} catch (e: Exception) {
						// Skip malformed chapters instead of crashing
						continue
					}
				}
				chapters.reversed()
			},
		)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// Ensure we always use the proper chapter URL format: /chapter/MangaName/ChapterID.html
		val chapterUrl = if (chapter.url.startsWith("/chapter/")) {
			"https://$domain${chapter.url}"
		} else {
			chapter.url.toAbsoluteUrl(domain)
		}

		// Validate URL to avoid redirects to external sites
		if (!chapterUrl.contains(domain)) {
			throw ParseException("Invalid chapter URL", chapter.url)
		}

		val cookies = context.cookieJar.getCookies(domain)
		val headers = getRequestHeaders().newBuilder()
			.add("Referer", "https://$domain/")
			.apply {
				if (cookies.isNotEmpty()) {
					add("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
				}
			}
			.build()

		val doc = webClient.httpGet(chapterUrl, headers).parseHtml()

		// Check for page selector dropdown first
		val pageSelect = doc.selectFirst("select.change_pic_page")
			?: doc.selectFirst("select.sl-page")
			?: doc.selectFirst("select#page")
			?: doc.selectFirst("select[name=page]")

		// Try to get total pages from the download link text (e.g., "1 / 57")
		val totalPagesText = doc.selectFirst("a.pic_download")?.text()?.substringAfter("/")?.trim()?.toIntOrNull()

		if (totalPagesText != null) {
			// Generate multi-image page URLs (each contains ~10 images)
			val baseUrl = chapter.url.toAbsoluteUrl(domain)
			val allPageUrls = mutableListOf<String>()

			// Each page contains 10 images, calculate how many pages we need
			val imagesPerPage = 10
			val totalPages = (totalPagesText + imagesPerPage - 1) / imagesPerPage // Ceiling division

			for (pageNum in 1..totalPages) {
				// Generate URL: chapter-id-10-pageNumber.html (each page has 10 images)
				allPageUrls.add(baseUrl.replace(".html", "-10-$pageNum.html"))
			}

			val allPages = mutableListOf<MangaPage>()

			// Process all page URLs to collect images in order
			for (pageUrl in allPageUrls) {
				val pageDoc = if (pageUrl == chapter.url.toAbsoluteUrl(domain)) {
					doc // Use already loaded first page
				} else {
					try {
						webClient.httpGet(pageUrl, headers).parseHtml()
					} catch (e: Exception) {
						continue // Skip failed pages
					}
				}

				// Get ALL images from this page
				val images = pageDoc.select("img.manga_pic")
				for (img in images) {
					val imgUrl = img.attrAsAbsoluteUrl("src")
					if (imgUrl.isNotEmpty() && !allPages.any { it.url == imgUrl }) {
						allPages.add(MangaPage(generateUid(imgUrl), imgUrl, null, source))
					}
				}
			}

			// If no images found, fallback to old behavior
			if (allPages.isEmpty()) {
				return allPageUrls.map { url ->
					MangaPage(generateUid(url), url, null, source)
				}
			}

			return allPages
		}

		// Fallback: Check the already-loaded document for script
		val scriptContent = doc.select("script").find { script ->
			script.html().contains("all_imgs_url") && script.html().contains("[")
		}?.html()

		if (scriptContent != null) {
			val imageUrls = extractImageUrlsFromScript(scriptContent)
			if (imageUrls.isNotEmpty()) {
				return imageUrls.mapIndexed { index, imageUrl ->
					MangaPage(
						id = generateUid("${chapter.id}-$index"),
						url = imageUrl,
						preview = null,
						source = source
					)
				}
			}
		}


		// Last resort: return empty list instead of crashing
		return emptyList()
	}

	private fun extractImageUrlsFromScript(scriptContent: String): List<String> {
		try {
			// Find the all_imgs_url array
			val startIndex = scriptContent.indexOf("all_imgs_url: [")
			if (startIndex == -1) return emptyList()

			val arrayStart = startIndex + "all_imgs_url: ".length
			var depth = 0
			var inString = false
			var escape = false
			var arrayEnd = arrayStart

			// Find the matching closing bracket
			for (i in arrayStart until scriptContent.length) {
				val char = scriptContent[i]

				when {
					escape -> escape = false
					char == '\\' -> escape = true
					char == '"' && !escape -> inString = !inString
					!inString -> {
						when (char) {
							'[' -> depth++
							']' -> {
								depth--
								if (depth == 0) {
									arrayEnd = i + 1
									break
								}
							}
						}
					}
				}
			}

			if (depth != 0) return emptyList()

			// Extract the array content
			val arrayContent = scriptContent.substring(arrayStart, arrayEnd)

			// Extract URLs using regex
			val urlRegex = Regex("""["']([^"']+\.(?:webp|jpg|jpeg|png)[^"']*)["']""")
			return urlRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()

		} catch (e: Exception) {
			return emptyList()
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		// Check if it's already a direct image URL (including ones with query parameters)
		val urlWithoutQuery = page.url.substringBefore('?')
		if (urlWithoutQuery.endsWith(".jpg", true) || urlWithoutQuery.endsWith(".png", true) ||
			urlWithoutQuery.endsWith(".jpeg", true) || urlWithoutQuery.endsWith(".webp", true)) {
			return page.url
		}

		val url = page.url.toAbsoluteUrl(domain)
		val cookies = context.cookieJar.getCookies(domain)
		val headers = getRequestHeaders().newBuilder()
			.add("Referer", "https://$domain/")
			.apply {
				if (cookies.isNotEmpty()) {
					add("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
				}
			}
			.build()

		val doc = webClient.httpGet(url, headers).parseHtml()
		val root = doc.body()
		return root.selectFirst("img.manga_pic")?.attrAsAbsoluteUrl("src")
			?: root.selectFirst("a.pic_download")?.attrAsAbsoluteUrl("href")
			?: throw ParseException("Could not find image URL", page.url)
	}

	private var tagCache: ArrayMap<String, MangaTag>? = null
	private val mutex = Mutex()

	private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
		tagCache?.let { return@withLock it }
		val tagMap = ArrayMap<String, MangaTag>()
		val tagElements = captureDocument("https://${domain}/search/?type=high").select("li.cate_list")
		for (el in tagElements) {
			if (el.text().isEmpty()) continue
			val cateId = el.attr("cate_id")
			val a = el.selectFirstOrThrow("a")
			tagMap[el.text()] = MangaTag(
				title = a.text().toTitleCase(sourceLocale),
				key = cateId,
				source = source,
			)
		}
		tagCache = tagMap
		return@withLock tagMap
	}

	private suspend fun captureDocument(url: String): Document {
		val script = """
			(() => {
				const title = document.title.toLowerCase();
				const bodyText = document.body.innerText;

				const hasBlockedTitle = title.includes('access denied');
				const hasFake404 = title.includes('404 not found') && bodyText.includes('the site is closed');
				const hasActiveChallengeForm = document.querySelector('form[action*="__cf_chl"]') !== null;
				const hasChallengeScript = document.querySelector('script[src*="challenge-platform"]') !== null;

				if (hasBlockedTitle || hasFake404 || hasActiveChallengeForm || hasChallengeScript) {
					return "CLOUDFLARE_BLOCKED";
				}

				// Check for all_imgs_url script first - return immediately if found
				const scripts = document.querySelectorAll('script');
				for (let script of scripts) {
					if (script.innerHTML && script.innerHTML.includes('all_imgs_url') && script.innerHTML.includes('[')) {
						window.stop();
						const elementsToRemove = document.querySelectorAll('iframe, object, embed, style');
						elementsToRemove.forEach(el => el.remove());
						return document.documentElement.outerHTML;
					}
				}

				const hasContent = document.querySelector('ul#list_container') !== null ||
								   document.querySelector('div.book-left') !== null ||
								   document.querySelector('div.book-info') !== null ||
								   document.getElementById('page') !== null ||
								   document.querySelector('a.pic_download') !== null ||
								   document.querySelector('img.manga_pic') !== null ||
								   document.querySelector('li.cate_list') !== null;

				if (hasContent) {
					window.stop();
					const elementsToRemove = document.querySelectorAll('script, iframe, object, embed, style');
					elementsToRemove.forEach(el => el.remove());
					return document.documentElement.outerHTML;
				}
				return null;
			})();
		""".trimIndent()

		val rawHtml = context.evaluateJs(url, script, 30000L) ?: throw ParseException("Failed to load page", url)

		val html = rawHtml.let { raw ->
			val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
				raw.substring(1, raw.length - 1)
					.replace("\\\"", "\"")
					.replace("\\n", "\n")
					.replace("\\r", "\r")
					.replace("\\t", "\t")
			} else raw

			unquoted.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
				val hexValue = match.groupValues[1]
				hexValue.toInt(16).toChar().toString()
			}
		}

		if (html == "CLOUDFLARE_BLOCKED") {
			context.requestBrowserAction(this, url)
			throw ParseException("Cloudflare challenge detected", url)
		}

		return Jsoup.parse(html, url)
	}

	private fun parseStatus(status: String) = when {
		// en
		status.contains("Ongoing") -> MangaState.ONGOING
		status.contains("Completed") -> MangaState.FINISHED
		//es
		status.contains("En curso") -> MangaState.ONGOING
		status.contains("Completado") -> MangaState.FINISHED
		//ru
		status.contains("постоянный") -> MangaState.ONGOING
		status.contains("завершенный") -> MangaState.FINISHED
		//de
		status.contains("Laufende") -> MangaState.ONGOING
		status.contains("Abgeschlossen") -> MangaState.FINISHED
		//pt
		status.contains("Completo") -> MangaState.ONGOING
		status.contains("Em tradução") -> MangaState.FINISHED
		//it
		status.contains("In corso") -> MangaState.ONGOING
		status.contains("Completato") -> MangaState.FINISHED
		//fr
		status.contains("En cours") -> MangaState.ONGOING
		status.contains("Complété") -> MangaState.FINISHED
		else -> null
	}

	private fun parseChapterDateByLang(date: String): Long {
		if (date.isBlank()) return 0L

		val dateWords = date.split(" ")
		if (dateWords.size != 3) return 0L

		return try {
			if (dateWords[1].contains(",")) {
				SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH).parseSafe(date)
			} else {
				val timeAgo = dateWords[0].toIntOrNull() ?: return 0L
				val timeUnit = when (dateWords[1]) {
					"minutes", "minutos", "минут", "minuti" -> Calendar.MINUTE
					"hours", "horas", "hora", "часа", "Stunden", "ore", "heures" -> Calendar.HOUR
					else -> return 0L
				}

				System.currentTimeMillis() - (timeAgo * if (timeUnit == Calendar.MINUTE) 60_000L else 3_600_000L)
			}
		} catch (e: Exception) {
			0L
		}
	}

	@MangaSourceParser("NINEMANGA_EN", "NineManga English", "en")
	class English(context: MangaLoaderContext) : NineMangaParser(
		context,
		MangaParserSource.NINEMANGA_EN,
		"ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_ES", "NineManga Español", "es")
	class Spanish(context: MangaLoaderContext) : NineMangaParser(
		context,
		MangaParserSource.NINEMANGA_ES,
		"es.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_RU", "NineManga Русский", "ru")
	class Russian(context: MangaLoaderContext) : NineMangaParser(
		context,
		MangaParserSource.NINEMANGA_RU,
		"ru.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_DE", "NineManga Deutsch", "de")
	class Deutsch(context: MangaLoaderContext) : NineMangaParser(
		context,
		MangaParserSource.NINEMANGA_DE,
		"de.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_BR", "NineManga Brasil", "pt")
	class Brazil(context: MangaLoaderContext) : NineMangaParser(
		context,
		MangaParserSource.NINEMANGA_BR,
		"br.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_IT", "NineManga Italiano", "it")
	class Italiano(context: MangaLoaderContext) : NineMangaParser(
		context,
		MangaParserSource.NINEMANGA_IT,
		"it.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_FR", "NineManga Français", "fr")
	class Francais(context: MangaLoaderContext) : NineMangaParser(
		context,
		MangaParserSource.NINEMANGA_FR,
		"fr.ninemanga.com",
	)
}
