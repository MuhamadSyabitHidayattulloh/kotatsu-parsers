package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("WESTMANGA", "WestManga", "id")
internal class WestmangaParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.WESTMANGA, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("westmanga.me")

	private val apiDomain = "data.westmanga.me"
	private val accessKey = "WM_WEB_FRONT_END"
	private val secretKey = "xxxoidj"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$apiDomain/api/contents".toHttpUrl().newBuilder().apply {
			if (!filter.query.isNullOrEmpty()) {
				addQueryParameter("q", filter.query)
			}
			addQueryParameter("page", page.toString())
			addQueryParameter("per_page", "20")
			addQueryParameter("type", "Comic")

			when (order) {
				SortOrder.UPDATED -> addQueryParameter("sort", "updated_at")
				SortOrder.POPULARITY -> addQueryParameter("sort", "views")
				SortOrder.ALPHABETICAL -> addQueryParameter("sort", "title")
				SortOrder.NEWEST -> addQueryParameter("sort", "created_at")
				else -> {}
			}

			filter.states.oneOrThrowIfMany()?.let {
				addQueryParameter("status", when (it) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					MangaState.PAUSED -> "hiatus"
					else -> return@let
				})
			}

			filter.types.oneOrThrowIfMany()?.let {
				addQueryParameter("country", when (it) {
					ContentType.MANGA -> "JP"
					ContentType.MANHWA -> "KR"
					ContentType.MANHUA -> "CN"
					else -> return@let
				})
			}
		}.build()

		val response = webClient.httpGet(url, createApiHeaders(url)).parseJson()
		val mangaArray = response.getJSONArray("data")

		return mangaArray.mapJSON { jo ->
			val slug = jo.getString("slug")
			Manga(
				id = generateUid(slug),
				title = jo.getString("title"),
				altTitles = emptySet(),
				url = "/manga/$slug/",
				publicUrl = "https://$domain/comic/$slug",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = jo.getString("cover"),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removeSurrounding("/manga/", "/")
		val url = "https://$apiDomain/api/comic/$slug".toHttpUrl()

		val data = webClient.httpGet(url, createApiHeaders(url)).parseJson()

		val tags = buildSet {
			when (data.getStringOrNull("country_id") ?: data.getStringOrNull("country")) {
				"JP" -> add(MangaTag("Manga", "manga", source))
				"CN" -> add(MangaTag("Manhua", "manhua", source))
				"KR" -> add(MangaTag("Manhwa", "manhwa", source))
			}
			if (data.optBoolean("color", false)) {
				add(MangaTag("Colored", "colored", source))
			}
			data.optJSONArray("genres")?.let { genres ->
				for (i in 0 until genres.length()) {
					val genre = genres.getJSONObject(i)
					add(MangaTag(
						title = genre.getString("name"),
						key = genre.getStringOrNull("slug") ?: genre.getString("name"),
						source = source,
					))
				}
			}
		}

		val description = buildString {
			data.getStringOrNull("synopsis")?.let { synopsis ->
				append(org.jsoup.Jsoup.parseBodyFragment(synopsis).wholeText().trim())
			}
			data.getStringOrNull("alternative_name")?.let { alt ->
				append("\n\nAlternative Name: ")
				append(alt.trim())
			}
		}

		val state = when (data.getStringOrNull("status")) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			else -> null
		}

		val chapters = data.optJSONArray("chapters")?.mapJSON { chapterObj ->
			val chapterSlug = chapterObj.getString("slug")
			val updatedAt = chapterObj.optJSONObject("updated_at")?.getLongOrDefault("time", 0L)
				?: chapterObj.getLongOrDefault("updated_at", 0L)
			MangaChapter(
				id = generateUid(chapterSlug),
				url = "/$chapterSlug/",
				title = "Chapter ${chapterObj.getString("number")}",
				number = chapterObj.getString("number").toFloatOrNull() ?: 0f,
				volume = 0,
				branch = null,
				uploadDate = updatedAt * 1000,
				scanlator = null,
				source = source,
			)
		} ?: emptyList()

		return manga.copy(
			title = data.getString("title"),
			coverUrl = data.getString("cover"),
			tags = tags,
			authors = setOfNotNull(data.getStringOrNull("author")),
			description = description.takeIf { it.isNotEmpty() },
			state = state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.removeSurrounding("/", "/")
		val url = "https://$apiDomain/api/v/$slug".toHttpUrl()

		val response = webClient.httpGet(url, createApiHeaders(url)).parseJson()
		val images = response.optJSONObject("data")?.getJSONArray("images")
			?: response.getJSONArray("images")

		return images.mapJSONIndexed { index, _ ->
			MangaPage(
				id = generateUid(images.getString(index)),
				url = images.getString(index),
				preview = null,
				source = source,
			)
		}
	}

	private fun createApiHeaders(url: okhttp3.HttpUrl): Headers {
		val timestamp = (System.currentTimeMillis() / 1000).toString()
		val message = "wm-api-request"
		val key = timestamp + "GET" + url.encodedPath + accessKey + secretKey

		val mac = Mac.getInstance("HmacSHA256")
		val secretKeySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
		mac.init(secretKeySpec)
		val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
		val signature = hash.joinToString("") { "%02x".format(it) }

		return Headers.Builder()
			.add("User-Agent", config[userAgentKey])
			.add("Referer", "https://$domain/")
			.add("x-wm-request-time", timestamp)
			.add("x-wm-accses-key", accessKey)
			.add("x-wm-request-signature", signature)
			.build()
	}
}
