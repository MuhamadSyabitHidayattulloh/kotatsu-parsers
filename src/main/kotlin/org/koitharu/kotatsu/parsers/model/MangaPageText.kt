package org.koitharu.kotatsu.parsers.model

/**
 * Represents a text overlay on a manga page image for MTL (Machine Translation Layer) support.
 * This is used for manga sources that provide translated text separately from images,
 * with coordinates to position the text over the image.
 */
public data class MangaPageText(
	/**
	 * The bounding box coordinates for the text overlay [left, top, right, bottom]
	 * or [left, top, width, height] depending on the source implementation
	 */
	@JvmField public val box: List<Int>,
	/**
	 * The translated text to display
	 */
	@JvmField public val text: String,
)

/**
 * Represents OCR/translation data for a specific manga page image.
 * Used by MTL sources to provide text overlays.
 */
public data class MangaPageOcrData(
	/**
	 * The image filename this OCR data belongs to
	 */
	@JvmField public val image: String,
	/**
	 * List of text overlays for this image
	 */
	@JvmField public val texts: List<MangaPageText>,
)
