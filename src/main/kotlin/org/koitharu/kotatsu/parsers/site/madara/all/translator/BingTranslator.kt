package org.koitharu.kotatsu.parsers.site.madara.all.translator

import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJsonArray

internal class BingTranslator(
	private val webClient: WebClient,
	private val domain: String,
) {

	private val baseUrl = "https://www.bing.com"
	private val translatorUrl = "$baseUrl/translator"

	private var tokens: TokenGroup = TokenGroup()

	private val attempts = 3

	suspend fun translate(from: String, to: String, text: String): String {
		if (text.isBlank()) return text

		if (!tokens.isValid() && !refreshTokens()) {
			return text
		}

		val request = translatorRequest(from, to, text)
		repeat(attempts) {
			try {
				return fetchTranslatedText(request)
			} catch (e: Exception) {
				refreshTokens()
			}
		}
		return text
	}

	private suspend fun fetchTranslatedText(requestData: TranslatorRequestData): String {
		val response = webClient.httpPost(requestData.url, requestData.form)
		val jsonArray = response.parseJsonArray()
		return jsonArray.getJSONObject(0).getString("translations")
			.let { org.json.JSONArray(it) }
			.getJSONObject(0)
			.getString("text")
	}

	private suspend fun refreshTokens(): Boolean {
		tokens = loadTokens()
		return tokens.isValid()
	}

	private fun translatorRequest(from: String, to: String, text: String): TranslatorRequestData {
		val url = buildString {
			append("$baseUrl/ttranslatev3")
			append("?isVertical=1")
			append("&")
			append("&IG=${tokens.ig}")
			append("&IID=${tokens.iid}")
		}

		val form = mapOf(
			"fromLang" to from,
			"to" to to,
			"text" to text,
			"tryFetchingGenderDebiasedTranslations" to "true",
			"token" to tokens.token,
			"key" to tokens.key,
		)

		return TranslatorRequestData(url, form)
	}

	private suspend fun loadTokens(): TokenGroup {
		val doc = webClient.httpGet(translatorUrl).parseHtml()

		val scripts = doc.select("script").map { it.data() }

		val scriptOne = scripts.firstOrNull { TOKENS_REGEX.containsMatchIn(it) }
			?: return TokenGroup()

		val scriptTwo = scripts.firstOrNull { IG_PARAM_REGEX.containsMatchIn(it) }
			?: return TokenGroup()

		val matchOne = TOKENS_REGEX.find(scriptOne)?.groups
		val matchTwo = IG_PARAM_REGEX.find(scriptTwo)?.groups

		return TokenGroup(
			token = matchOne?.get(4)?.value ?: "",
			key = matchOne?.get(3)?.value ?: "",
			ig = matchTwo?.get(1)?.value ?: "",
			iid = doc.selectFirst("div[data-iid]:not([class])")?.attr("data-iid") ?: "",
		)
	}

	private data class TokenGroup(
		val token: String = "",
		val key: String = "",
		val ig: String = "",
		val iid: String = "",
	) {
		fun isValid(): Boolean = token.isNotEmpty() && key.isNotEmpty() && ig.isNotEmpty() && iid.isNotEmpty()
	}

	private data class TranslatorRequestData(
		val url: String,
		val form: Map<String, String>,
	)

	companion object {
		private val TOKENS_REGEX = """params_AbusePreventionHelper(\s+)?=(\s+)?[^\[]\[(\d+),"([^"]+)""".toRegex()
		private val IG_PARAM_REGEX = """IG:"([^"]+)""".toRegex()
	}
}
