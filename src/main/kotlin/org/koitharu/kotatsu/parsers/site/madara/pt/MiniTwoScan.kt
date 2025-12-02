package org.koitharu.kotatsu.parsers.site.madara.pt

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MINITWOSCAN", "MiniTwoScan", "pt")
internal class MiniTwoScan(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MINITWOSCAN, "minitwoscan.com") {

    // Force direct chapter loading (no ajax)
    override val selectTestAsync = "li.wp-manga-chapter, div.wp-manga-chapter"
}