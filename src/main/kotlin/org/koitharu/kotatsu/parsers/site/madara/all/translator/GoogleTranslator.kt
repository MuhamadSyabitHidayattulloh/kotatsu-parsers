package org.koitharu.kotatsu.parsers.site.madara.all.translator

import org.json.JSONArray
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.parseJsonArray

internal class GoogleTranslator(
	private val webClient: WebClient,
	private val domain: String,
) {

	private val baseUrl = "https://translate.googleapis.com"
	private val webpage = "https://translate.google.com"
	private val translatorUrl = "$baseUrl/translate_a/single"

	suspend fun translate(from: String, to: String, text: String): String {
		if (text.isBlank()) return text

		return try {
			val url = buildTranslatorUrl(text, from, to)
			fetchTranslatedText(url)
		} catch (e: Exception) {
			text
		}
	}

	private fun buildTranslatorUrl(text: String, from: String, to: String): String {
		val dtParams = arrayOf("at", "bd", "ex", "ld", "md", "qca", "rw", "rm", "ss", "t")
		val params = buildList {
			add("client=gtx")
			add("sl=$from")
			add("tl=$to")
			add("hl=$to")
			add("ie=UTF-8")
			add("oe=UTF-8")
			add("otf=1")
			add("ssel=0")
			add("tsel=0")
			add("tk=xxxx")
			add("q=$text")
			dtParams.forEach { dt ->
				add("dt=$dt")
			}
		}

		return "$translatorUrl?${params.joinToString("&")}"
	}

	private suspend fun fetchTranslatedText(url: String): String {
		val response = webClient.httpGet(url)
		if (!response.isSuccessful) {
			throw Exception("Request failed: ${response.code}")
		}

		val jsonData = response.parseJsonArray()
		return extractTranslatedText(jsonData)
	}

	private fun extractTranslatedText(data: JSONArray): String {
		val firstArray = data.getJSONArray(0)
		return buildString {
			for (i in 0 until firstArray.length()) {
				val item = firstArray.getJSONArray(i)
				append(item.getString(0))
			}
		}
	}
}
