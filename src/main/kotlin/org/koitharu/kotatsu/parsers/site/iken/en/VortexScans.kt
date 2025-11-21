package org.koitharu.kotatsu.parsers.site.iken.en

import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.iken.IkenParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.json.JSONArray

@MangaSourceParser("VORTEXSCANS", "VortexScans", "en")
internal class VortexScans(context: MangaLoaderContext) :
	IkenParser(context, MangaParserSource.VORTEXSCANS, "vortexscans.org", 18, true) {

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)

		val script = """
			(() => {
				const title = document.title.toLowerCase();
				const bodyText = document.body.innerText;

				const hasBlockedTitle = title.includes('access denied') || title.includes('just a moment');
				const hasCloudflareChallenge = document.querySelector('div.cf-wrapper') !== null ||
					document.querySelector('div[class*="cf-"]') !== null ||
					document.querySelector('script[src*="challenge-platform"]') !== null;

				if (hasBlockedTitle || hasCloudflareChallenge) {
					return "CLOUDFLARE_BLOCKED";
				}

				const hasContent = document.querySelector('main section img') !== null ||
					document.querySelector('script') !== null;

				if (hasContent) {
					window.stop();
					const elementsToRemove = document.querySelectorAll('script[src], iframe, object, embed');
					elementsToRemove.forEach(el => el.remove());
					return document.documentElement.outerHTML;
				}
				return null;
			})();
		""".trimIndent()

		val rawHtml = context.evaluateJs(fullUrl, script, 30000L)
			?: throw ParseException("Failed to load chapter page", fullUrl)

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
			context.requestBrowserAction(this, fullUrl)
			throw ParseException("Cloudflare challenge detected", fullUrl)
		}

		val doc = Jsoup.parse(html, fullUrl)

		if (doc.selectFirst("svg.lucide-lock") != null) {
			throw Exception("Need to unlock chapter!")
		}

		val imagesJson = doc.getNextJson("images")
		val images = parseImagesJson(imagesJson)

		return images.map { p ->
			MangaPage(
				id = generateUid(p),
				url = p,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseImagesJson(json: String): List<String> {
		val jsonArray = JSONArray(json)
		return List(jsonArray.length()) { index ->
			val item = jsonArray.getJSONObject(index)
			item.getString("url")
		}
	}
}
