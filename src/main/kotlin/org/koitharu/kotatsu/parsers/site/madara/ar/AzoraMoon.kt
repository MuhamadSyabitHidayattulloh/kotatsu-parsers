package org.koitharu.kotatsu.parsers.site.madara.ar

import kotlinx.coroutines.delay
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.mapToSet
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("AZORAMOON", "AzoraMoon", "ar")
internal class AzoraMoon(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.AZORAMOON, "azoramoon.com", pageSize = 10) {
	override val tagPrefix = "series-genre/"
	override val listUrl = "series/"

	// Rate limiting and caching
	private val tagCache = ConcurrentHashMap<String, CachedTags>()
	private val filterOptionsCache = ConcurrentHashMap<String, CachedFilterOptions>()
	private var lastRequestTime = 0L
	private val minRequestInterval = 800L // 800ms between requests

	private data class CachedTags(
		val tags: Set<MangaTag>,
		val timestamp: Long
	) {
		fun isValid(): Boolean = System.currentTimeMillis() - timestamp < 24 * 60 * 60 * 1000L // 24 hours
	}

	private data class CachedFilterOptions(
		val options: MangaListFilterOptions,
		val timestamp: Long
	) {
		fun isValid(): Boolean = System.currentTimeMillis() - timestamp < 24 * 60 * 60 * 1000L // 24 hours
	}

	// Rate limiting helper
	private suspend fun rateLimit() {
		val currentTime = System.currentTimeMillis()
		val timeSinceLastRequest = currentTime - lastRequestTime
		if (timeSinceLastRequest < minRequestInterval) {
			delay(minRequestInterval - timeSinceLastRequest)
		}
		lastRequestTime = System.currentTimeMillis()
	}

	// Override tag fetching with caching and rate limiting
	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val cacheKey = "tags"
		val cached = tagCache[cacheKey]

		// Return cached tags if valid
		if (cached != null && cached.isValid()) {
			return cached.tags
		}

		// Apply rate limiting before making request
		rateLimit()

		try {
			val tags = super.fetchAvailableTags()
			tagCache[cacheKey] = CachedTags(tags, System.currentTimeMillis())
			return tags
		} catch (e: Exception) {
			// If request fails, return cached tags if available (even if expired)
			cached?.let { return it.tags }
			throw e
		}
	}

	// Override filter options with caching
	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val cacheKey = "filter_options"
		val cached = filterOptionsCache[cacheKey]

		// Return cached filter options if valid
		if (cached != null && cached.isValid()) {
			return cached.options
		}

		// Apply rate limiting before making request
		rateLimit()

		try {
			val options = MangaListFilterOptions(
				availableTags = fetchAvailableTags(),
				availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
				availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
			)
			filterOptionsCache[cacheKey] = CachedFilterOptions(options, System.currentTimeMillis())
			return options
		} catch (e: Exception) {
			// If request fails, return cached options if available (even if expired)
			cached?.let { return it.options }
			throw e
		}
	}

	// Override list page with rate limiting
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		rateLimit()
		return super.getListPage(page, order, filter)
	}

	// Override details with rate limiting
	override suspend fun getDetails(manga: Manga): Manga {
		rateLimit()
		return super.getDetails(manga)
	}

	// Override pages with rate limiting
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		rateLimit()
		return super.getPages(chapter)
	}
}
