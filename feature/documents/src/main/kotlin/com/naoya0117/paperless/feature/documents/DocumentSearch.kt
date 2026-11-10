package com.naoya0117.paperless.feature.documents

/** A rectangle on a page, as fractions of the page's width and height (0..1, from the top left). */
data class PageRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Where a search found the text. */
sealed interface SearchMatch {
    /** On a PDF page, with the rectangles of the text (several if it spans lines). */
    data class InPage(
        val page: Int,
        val bounds: List<PageRect>,
    ) : SearchMatch

    /** In the document's text (from the server, e.g. OCR), with some text around it. */
    data class InText(
        val before: String,
        val match: String,
        val after: String,
    ) : SearchMatch
}

/** Searching within the document. */
data class SearchState(
    val open: Boolean = false,
    val query: String = "",
    /** A search for [query] is running. */
    val searching: Boolean = false,
    val matches: List<SearchMatch> = emptyList(),
    /** The match shown (an index into [matches]). */
    val current: Int = 0,
) {
    val currentMatch: SearchMatch? get() = matches.getOrNull(current)
}

/** Every occurrence of [query] in [text] (ignoring case), with [context] characters around it. */
internal fun searchText(
    text: String,
    query: String,
    context: Int = SNIPPET_CONTEXT,
): List<SearchMatch.InText> {
    if (query.isBlank()) return emptyList()
    val matches = mutableListOf<SearchMatch.InText>()
    var from = 0
    while (matches.size < MAX_TEXT_MATCHES) {
        val start = text.indexOf(query, from, ignoreCase = true)
        if (start < 0) break
        val end = start + query.length
        matches +=
            SearchMatch.InText(
                before = text.substring((start - context).coerceAtLeast(0), start).oneLine(),
                match = text.substring(start, end),
                after = text.substring(end, (end + context).coerceAtMost(text.length)).oneLine(),
            )
        from = end
    }
    return matches
}

private fun String.oneLine() = replace(Regex("""\s+"""), " ")

private const val SNIPPET_CONTEXT = 60
private const val MAX_TEXT_MATCHES = 500
