package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl

@MangaSourceParser("LEITORDEMANGA", "LeitorDeManga", "pt")
internal class LeitorDeManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.LEITORDEMANGA, "leitordemanga.com", 10) {
	override val datePattern = "dd/MM/yyyy"
	override val listUrl = "ler-manga/"

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)

		// Check if we have the hcdn cookie
		val cookies = context.cookieJar.getCookies(domain)
		val hasHcdnCookie = cookies.any { it.name == "hcdn" }

		val doc = if (hasHcdnCookie) {
			// If we have the cookie, use regular HTTP request
			val headers = getRequestHeaders().newBuilder()
				.add("Referer", "https://$domain/")
				.add("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
				.build()
			webClient.httpGet(fullUrl, headers).parseHtml()
		} else {
			// If we don't have the cookie, use evaluateJs to handle browser check
			captureDocument(fullUrl)
		}

		return super.parseMangaPages(doc, chapter)
	}

	private suspend fun captureDocument(url: String): Document {
		val script = """
			(() => {
				const hasContent = document.querySelector('div.reading-content') !== null ||
								   document.querySelector('div.page-break') !== null ||
								   document.querySelector('img[data-src]') !== null;

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
