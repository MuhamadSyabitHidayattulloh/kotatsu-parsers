package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("PEACHBL", "PeachBl", "ar", ContentType.HENTAI)
internal class PeachBl(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.PEACHBL, "peach-bl.com", pageSize = 20, searchPageSize = 10) {
	override val sourceLocale: Locale = Locale.ENGLISH

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			when {
				!filter.query.isNullOrEmpty() -> {
					// New search URL structure: https://peach-bl.com/?post_type=webtoon&s=mary
					append("/?post_type=webtoon&s=")
					append(filter.query.urlEncoded())
					if (page > 1) {
						append("&paged=")
						append(page.toString())
					}
				}

				else -> {
					// New list URL structure: https://peach-bl.com/?s=
					append("/?s=")
					if (page > 1) {
						append("&paged=")
						append(page.toString())
					}

					// Add order parameter if needed
					when (order) {
						SortOrder.ALPHABETICAL -> append("&orderby=title&order=ASC")
						SortOrder.ALPHABETICAL_DESC -> append("&orderby=title&order=DESC")
						SortOrder.NEWEST -> append("&orderby=date&order=DESC")
						SortOrder.POPULARITY -> append("&orderby=meta_value_num&meta_key=_wp_manga_views&order=DESC")
						SortOrder.UPDATED -> append("&orderby=modified&order=DESC")
						else -> {}
					}

					filter.tags.forEach { tag ->
						append("&genre[]=")
						append(tag.key)
					}

					filter.tagsExclude.forEach { tag ->
						append("&genre[]=-")
						append(tag.key)
					}

					if (filter.states.isNotEmpty()) {
						filter.states.oneOrThrowIfMany()?.let { state ->
							append("&status=")
							when (state) {
								MangaState.ONGOING -> append("ongoing")
								MangaState.FINISHED -> append("completed")
								MangaState.PAUSED -> append("on-hold")
								MangaState.ABANDONED -> append("canceled")
								else -> {}
							}
						}
					}
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}
}
