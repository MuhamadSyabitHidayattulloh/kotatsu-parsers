package org.koitharu.kotatsu.parsers.site.mangabox.en

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangabox.MangaboxParser
import kotlin.collections.set


@MangaSourceParser("HMANGABAT", "MangaBat", "en")
internal class Mangabat(context: MangaLoaderContext) :
	MangaboxParser(context, MangaParserSource.HMANGABAT) {
	override val configKeyDomain = ConfigKey.Domain("mangabats.com")
	override val selectTagMap = "div.panel-category p.pn-category-row:not(.pn-category-row-border) a"

	// Ensure pagination URLs are correctly formatted for mangabats.com
	override val listUrl = "/manga-list.html"

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://www.mangabats.com/")
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Remove encoding headers from requests to avoid gzip compression issues
        if (originalRequest.method == "GET" || originalRequest.method == "POST") {
            val newRequest = originalRequest.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Accept-Encoding")
                .build()
            return chain.proceed(newRequest)
        }

        return chain.proceed(originalRequest)
    }

}
