package org.koitharu.kotatsu.parsers.site.madara.all

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaPageText
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.site.madara.all.translator.BingTranslator
import org.koitharu.kotatsu.parsers.site.madara.all.translator.GoogleTranslator
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.toJSONArrayOrNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.util.Locale

@MangaSourceParser("MANHUARM", "Manhuarm", "")
internal class Manhuarm(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHUARM, "manhuarmmtl.com") {
	override val sourceLocale: Locale = Locale.ENGLISH

	private val bingTranslator by lazy { BingTranslator(webClient, domain) }
	private val googleTranslator by lazy { GoogleTranslator(webClient, domain) }

	private val translatorModelKey = ConfigKey.TranslatorModel(
		presetValues = mapOf(
			"google" to "Google Translate",
			"bing" to "Bing Translator",
		),
		defaultValue = "google",
	)

	private val translatorLanguageKey = ConfigKey.TranslatorLanguage(
		presetValues = mapOf(
			"en" to "English",
			"id" to "Indonesian",
			"es" to "Spanish",
			"fr" to "French",
			"de" to "German",
			"ja" to "Japanese",
			"ko" to "Korean",
			"pt" to "Portuguese",
			"ru" to "Russian",
			"th" to "Thai",
			"vi" to "Vietnamese",
			"zh-CN" to "Chinese (Simplified)",
			"zh-TW" to "Chinese (Traditional)",
		),
		defaultValue = "en",
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(translatorModelKey)
		keys.add(translatorLanguageKey)
	}

	private suspend fun translateText(text: String): String {
		if (text.isBlank()) return text

		val targetLang = config[translatorLanguageKey]
		if (targetLang == "en") return text

		val model = config[translatorModelKey]

		return when (model) {
			"google" -> googleTranslator.translate("auto", targetLang, text)
			"bing" -> bingTranslator.translate("auto-detect", targetLang, text)
			else -> text
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val pages = super.getPages(chapter)
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val chapterId = doc.selectFirst("#wp-manga-current-chap")?.attr("data-id") ?: return pages

		val ocrData = try {
			webClient.httpGet("https://$domain/wp-content/uploads/ocr-data/$chapterId.json")
				.body.string().toJSONArrayOrNull()
		} catch (e: Exception) {
			null
		} ?: return pages

		val textMap = ocrData.mapJSON { pageData ->
			val imageUrl = pageData.getString("image")
			val texts = pageData.getJSONArray("texts").mapJSON { dialogue ->
				val box = dialogue.getJSONArray("box")
				val originalText = dialogue.getString("text")
				val translatedText = translateText(originalText)
				MangaPageText(
					rect = Rect(
						left = box.getInt(0),
						top = box.getInt(1),
						right = box.getInt(0) + box.getInt(2),
						bottom = box.getInt(1) + box.getInt(3),
					),
					text = translatedText,
				)
			}
			imageUrl to texts
		}.toMap()

		return pages.map { page ->
			val imageFileName = page.url.substringAfterLast('/')
			val texts = textMap.entries.firstOrNull { (key, _) ->
				key.contains(imageFileName, ignoreCase = true)
			}?.value

			if (!texts.isNullOrEmpty()) {
				MangaPage(
					id = page.id,
					url = page.url,
					preview = page.preview,
					source = page.source,
					texts = texts,
				)
			} else {
				page
			}
		}
	}
}