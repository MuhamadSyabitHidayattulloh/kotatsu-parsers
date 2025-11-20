package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAGEKO", "MangaGeko", "en")
internal class MangaGeko(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAGEKO, 30) {

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.NEWEST)

	override val configKeyDomain = ConfigKey.Domain("www.mgeko.cc", "www.mgeko.com", "www.mangageko.com")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					if (page > 1) {
						return emptyList()
					}
					append("/search/?search=")
					append(filter.query.urlEncoded())
				}

				else -> {

					append("/browse-comics/?results=")
					append(page)

					if (filter.tags.isNotEmpty()) {
						append("&tags_include=")
						append(filter.tags.joinToString(separator = ",") { it.key })
					}

					if (filter.tagsExclude.isNotEmpty()) {
						append("&tags_exclude=")
						append(filter.tagsExclude.joinToString(separator = ",") { it.key })
					}

					append("&filter=")
					when (order) {
						SortOrder.POPULARITY -> append("views")
						SortOrder.UPDATED -> append("Updated")
						SortOrder.NEWEST -> append("New")
						// SortOrder.RANDOM -> append("Random")
						else -> append("Updated")
					}
				}
			}
		}

		val doc = if (url.contains("/browse-comics/")) {
			captureDocument(url)
		} else {
			webClient.httpGet(url).parseHtml()
		}

		return doc.select("article.comic-card").map { article ->
			val href = article.selectFirstOrThrow("h3.comic-card__title a").attrAsRelativeUrl("href")
			val title = article.selectFirstOrThrow("h3.comic-card__title a").text()
			val coverUrl = article.selectFirst("div.comic-card__cover img")?.src() ?: ""

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/browse-comics/").parseHtml()
		return doc.select("button.chip[data-group='include_genres']").mapToSet { button ->
			val value = button.attr("data-value")
			val title = button.text()
			MangaTag(
				key = value,
				title = title,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chaptersDeferred = async { loadChapters(manga.url) }
		val author = doc.selectFirstOrThrow(".author").textOrNull()
		manga.copy(
			altTitles = setOfNotNull(doc.selectFirstOrThrow(".alternative-title").textOrNull()),
			state = when (doc.selectFirstOrThrow(".header-stats span:contains(Status) strong").text()) {
				"Ongoing" -> MangaState.ONGOING
				"Completed" -> MangaState.FINISHED
				else -> null
			},
			tags = doc.select(".categories ul li a").mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast('='),
					title = a.text(),
					source = source,
				)
			},
			authors = setOfNotNull(author),
			description = doc.selectFirstOrThrow(".description").html(),
			chapters = chaptersDeferred.await(),
		)
	}

	private suspend fun loadChapters(mangaUrl: String): List<MangaChapter> {
		val urlChapter = mangaUrl + "all-chapters/"
		val doc = webClient.httpGet(urlChapter.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat("MMM dd, yyyy", sourceLocale)
		return doc.requireElementById("chapters").select("ul.chapter-list li")
			.mapChapters(reversed = true) { i, li ->
				val a = li.selectFirstOrThrow("a")
				val url = a.attrAsRelativeUrl("href")
				val name = li.selectFirstOrThrow(".chapter-title").text()
				val dateText = li.select(".chapter-update").attr("datetime").substringBeforeLast(',')
					.replace(".", "").replace("Sept", "Sep")
				MangaChapter(
					id = generateUid(url),
					title = name,
					number = i + 1f,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(dateText),
					branch = null,
					source = source,
				)
			}
	}

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select("center img")
            .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
            // remove all invaild images + credits
            .filterNot { it.startsWith("data:image") || it.contains("credits-mgeko.png") }
            .distinct().map { url ->
                val finalUrl = url.toRelativeUrl(domain)
                MangaPage(
                    id = generateUid(finalUrl),
                    url = finalUrl,
                    preview = null,
                    source = source,
                )
            }
    }

	private suspend fun captureDocument(url: String): Document {
		val script = """
			(() => {
				// Check for different types of content
				const hasMangaList = document.querySelectorAll('article.comic-card').length > 0 ||
									 document.querySelector('button.chip[data-group="include_genres"]') !== null;

				const hasMangaDetails = document.querySelector('.author') !== null ||
										document.querySelector('.description') !== null ||
										document.querySelector('.categories') !== null;

				const hasChapterList = document.querySelector('#chapters') !== null ||
									   document.querySelector('ul.chapter-list') !== null;

				const hasChapterPages = document.querySelector('center img') !== null;

				// If any expected content is found, stop loading and return HTML
				if (hasMangaList || hasMangaDetails || hasChapterList || hasChapterPages) {
					window.stop();
					const elementsToRemove = document.querySelectorAll('script, iframe, object, embed, style');
					elementsToRemove.forEach(el => el.remove());
					return document.documentElement.outerHTML;
				}
				return null;
			})();
		""".trimIndent()

		val rawHtml = context.evaluateJs(url, script, 30000L) ?: throw ParseException("Failed to load page", url)

		val html = if (rawHtml.startsWith("\"") && rawHtml.endsWith("\"")) {
			rawHtml.substring(1, rawHtml.length - 1)
				.replace("\\\"", "\"")
				.replace("\\n", "\n")
				.replace("\\r", "\r")
				.replace("\\t", "\t")
				.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
					val hexValue = match.groupValues[1]
					hexValue.toInt(16).toChar().toString()
				}
		} else rawHtml

		return Jsoup.parse(html, url)
	}
}
