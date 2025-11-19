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
		.add("Accept-Language", "en-US;q=0.7,en;q=0.3")
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
				title = dd?.selectFirst("a")?.text()?.toCamelCase().orEmpty(),
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
		val description = infoRoot.select("dd.short-info > p").find { it.text().contains("Sinopse:", ignoreCase = true) }?.html()?.substringAfter("</span>")
		
		return manga.copy(
			title = infoRoot.selectFirst("h1")?.textOrNull()?.removeSuffix("Manga")?.trimEnd() ?: manga.title,
			tags = root.select("ul.inset-menu > li > a").mapNotNullToSet { 
				val text = it.text()
				if (text.isNotEmpty()) MangaTag(key = text, title = text, source = source) else null 
			},
			authors = setOfNotNull(author),
			state = statusText?.let { parseStatus(it) },
			description = description,
			chapters = root.selectFirst("ul.chapter-box")?.select("li")
				?.mapChapters(reversed = true) { i, li ->
					val a = li.selectFirst("div.chapter-name.long > a") ?: li.selectFirstOrThrow("div.chapter-name.short > a")
					val href = a.attrAsRelativeUrl("href").replace("%20", " ")
					MangaChapter(
						id = generateUid(href),
						title = a.textOrNull(),
						number = i + 1f,
						volume = 0,
						url = href,
						uploadDate = parseChapterDateByLang(li.selectFirst("div.add-time > span")?.text().orEmpty()),
						source = source,
						scanlator = null,
						branch = null,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = captureDocument(chapter.url.toAbsoluteUrl(domain))
		return doc.body().requireElementById("page").select("option").map { option ->
			val url = option.attr("value")
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val doc = captureDocument(page.url.toAbsoluteUrl(domain))
		val root = doc.body()
		return root.selectFirstOrThrow("a.pic_download").attrAsAbsoluteUrl("href")
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

				const hasContent = document.querySelector('ul#list_container') !== null ||
								   document.querySelector('div.book-left') !== null ||
								   document.getElementById('page') !== null ||
								   document.querySelector('a.pic_download') !== null ||
								   document.querySelector('li.cate_list') !== null;

				if (hasContent) {
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
		val dateWords = date.split(" ")

		if (dateWords.size == 3) {
			if (dateWords[1].contains(",")) {
				SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH).parseSafe(date)
			} else {
				val timeAgo = Integer.parseInt(dateWords[0])
				return Calendar.getInstance().apply {
					when (dateWords[1]) {
						"minutes" -> Calendar.MINUTE // EN-FR
						"hours" -> Calendar.HOUR // EN

						"minutos" -> Calendar.MINUTE // ES
						"horas" -> Calendar.HOUR

						// "minutos" -> Calendar.MINUTE // BR
						"hora" -> Calendar.HOUR

						"минут" -> Calendar.MINUTE // RU
						"часа" -> Calendar.HOUR

						"Stunden" -> Calendar.HOUR // DE

						"minuti" -> Calendar.MINUTE // IT
						"ore" -> Calendar.HOUR

						"heures" -> Calendar.HOUR // FR ("minutes" also French word)
						else -> null
					}?.let {
						add(it, -timeAgo)
					}
				}.timeInMillis
			}
		}
		return 0L
	}

	@MangaSourceParser("NINEMANGA_EN", "NineManga English", "en")
	class English(context: MangaLoaderContext) : NineMangaParser(
		context,
		MangaParserSource.NINEMANGA_EN,
		"www.ninemanga.com",
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
