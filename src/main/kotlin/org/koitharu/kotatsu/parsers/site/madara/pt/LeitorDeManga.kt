package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("LEITORDEMANGA", "LeitorDeManga", "pt")
internal class LeitorDeManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.LEITORDEMANGA, "leitordemanga.com", 10) {
	override val datePattern = "dd/MM/yyyy"
	override val listUrl = "ler-manga/"

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = captureDocument(fullUrl)

		// Use the same logic as parent but with captured document
		return manga.copy(
			chapters = loadChapters(manga.url, doc)
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val pages = page + 1
		val url = buildString {
			append("https://")
			append(domain)

			if (pages > 1) {
				append("/page/")
				append(pages.toString())
			}

			append(when {
				!filter.query.isNullOrEmpty() -> "/?s=${filter.query.urlEncoded()}&post_type=wp-manga"
				filter.tags.isNotEmpty() -> "/$tagPrefix${filter.tags.oneOrThrowIfMany()?.key}/"
				else -> "/$listUrl"
			})

			if (pages > 1 && filter.tags.isEmpty() && filter.query.isNullOrEmpty()) {
				append("page/$pages/")
			}
		}

		val doc = captureDocument(url)
		return parseMangaList(doc)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)

		// Always use captureDocument since the site always shows browser check
		val doc = captureDocument(fullUrl)

		// Parse using standard Madara selectors (no chapter protector)
		val root = doc.body().selectFirst(selectBodyPage) ?: throw ParseException(
			"No image found",
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

	private suspend fun captureDocument(url: String): Document {
		val script = """
			(() => {
				// Check for different types of content
				const hasReadingContent = document.querySelector('div.reading-content') !== null ||
										   document.querySelector('div.page-break') !== null ||
										   document.querySelector('img[data-src]') !== null;

				const hasMangaList = document.querySelector('div.page-listing-item') !== null ||
									 document.querySelector('div.page-item-detail') !== null ||
									 document.querySelector('.wp-manga-item') !== null;

				const hasMangaDetails = document.querySelector('div.summary_content') !== null ||
										document.querySelector('.manga-chapters') !== null ||
										document.querySelector('.post-title') !== null;

				// If any expected content is found, stop loading and return HTML
				if (hasReadingContent || hasMangaList || hasMangaDetails) {
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
