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

	// Comprehensive dynamic caching system
	private val tagCache = ConcurrentHashMap<String, CachedData<Set<MangaTag>>>()
	private val filterOptionsCache = ConcurrentHashMap<String, CachedData<MangaListFilterOptions>>()
	private val listPageCache = ConcurrentHashMap<String, CachedData<List<Manga>>>()
	private val mangaDetailsCache = ConcurrentHashMap<String, CachedData<Manga>>()
	private val chapterPagesCache = ConcurrentHashMap<String, CachedData<List<MangaPage>>>()
	private var lastRequestTime = 0L
	private val minRequestInterval = 1200L // Increased to 1.2 seconds for heavy rate limiting

	private data class CachedData<T>(
		val data: T,
		val timestamp: Long,
		val ttlMs: Long
	) {
		fun isValid(): Boolean = System.currentTimeMillis() - timestamp < ttlMs
		fun isExpiredButUsable(): Boolean = System.currentTimeMillis() - timestamp < ttlMs * 3 // Allow 3x TTL for fallback
	}

	// All cache TTLs set to 1 hour as requested
	companion object {
		private const val TAGS_TTL = 60 * 60 * 1000L // 1 hour
		private const val FILTER_OPTIONS_TTL = 60 * 60 * 1000L // 1 hour
		private const val LIST_PAGE_TTL = 60 * 60 * 1000L // 1 hour
		private const val MANGA_DETAILS_TTL = 60 * 60 * 1000L // 1 hour
		private const val CHAPTER_PAGES_TTL = 60 * 60 * 1000L // 1 hour
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

	// Helper function for caching with fallback
	private suspend inline fun <T> withCache(
		cache: ConcurrentHashMap<String, CachedData<T>>,
		key: String,
		ttl: Long,
		useRateLimit: Boolean = true,
		crossinline fetcher: suspend () -> T
	): T {
		val cached = cache[key]

		// If we have ANY cached data (even expired), return it to avoid rate limiting
		if (cached != null) {
			return cached.data
		}

		// Only make requests if we have NO cached data at all
		// Apply rate limiting only for restricted operations
		if (useRateLimit) {
			rateLimit()
		}

		try {
			val data = fetcher()
			cache[key] = CachedData(data, System.currentTimeMillis(), ttl)
			return data
		} catch (e: Exception) {
			// If this is the first request and it fails, we have no fallback
			throw e
		}
	}

	// Override tag fetching with caching and rate limiting (HEAVILY RESTRICTED)
	override suspend fun fetchAvailableTags(): Set<MangaTag> = withCache(
		cache = tagCache,
		key = "tags",
		ttl = TAGS_TTL,
		useRateLimit = true
	) {
		super.fetchAvailableTags()
	}

	// Override filter options with caching (HEAVILY RESTRICTED)
	override suspend fun getFilterOptions(): MangaListFilterOptions = withCache(
		cache = filterOptionsCache,
		key = "filter_options",
		ttl = FILTER_OPTIONS_TTL,
		useRateLimit = true
	) {
		MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
			availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		)
	}

	// Generate stable cache key for list pages
	private fun generateListCacheKey(page: Int, order: SortOrder, filter: MangaListFilter): String {
		val query = filter.query ?: ""
		val tags = filter.tags.sortedBy { it.key }.joinToString(",") { it.key }
		val states = filter.states.sorted().joinToString(",")
		val contentRating = filter.contentRating?.toString() ?: ""
		return "list_${page}_${order}_${query}_${tags}_${states}_${contentRating}"
	}

	// Override list page with caching and rate limiting (HEAVILY RESTRICTED)
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val cacheKey = generateListCacheKey(page, order, filter)
		val cached = listPageCache[cacheKey]

		// If we have ANY cached data, return it immediately to avoid AJAX request
		if (cached != null) {
			return cached.data
		}

		// Only if no cache exists, make the request with rate limiting
		rateLimit()

		try {
			val data = super.getListPage(page, order, filter)
			listPageCache[cacheKey] = CachedData(data, System.currentTimeMillis(), LIST_PAGE_TTL)
			return data
		} catch (e: Exception) {
			// No fallback possible for first request
			throw e
		}
	}

	// Manga details and chapter pages - NO RATE LIMITING (not restricted by server)
	override suspend fun getDetails(manga: Manga): Manga = withCache(
		cache = mangaDetailsCache,
		key = manga.url,
		ttl = MANGA_DETAILS_TTL,
		useRateLimit = false // NO rate limiting for manga details
	) {
		super.getDetails(manga)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = withCache(
		cache = chapterPagesCache,
		key = chapter.url,
		ttl = CHAPTER_PAGES_TTL,
		useRateLimit = false // NO rate limiting for chapter pages
	) {
		super.getPages(chapter)
	}
}
