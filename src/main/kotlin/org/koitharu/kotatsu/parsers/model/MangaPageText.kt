package org.koitharu.kotatsu.parsers.model

import org.koitharu.kotatsu.parsers.bitmap.Rect

public data class MangaPageText(
	/**
	 * The bounding box coordinates for the text overlay
	 */
	@JvmField public val rect: Rect,
	/**
	 * The translated text to display
	 */
	@JvmField public val text: String,
)
