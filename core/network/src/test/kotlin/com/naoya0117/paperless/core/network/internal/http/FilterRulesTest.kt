package com.naoya0117.paperless.core.network.internal.http

import com.naoya0117.paperless.core.model.FilterRule
import com.naoya0117.paperless.core.network.internal.dto.toQueryParams
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values follow the web UI's `queryParamsFromFilterRules` (src-ui/src/app/utils/query-params.ts). */
class FilterRulesTest {
    private fun params(vararg rules: Pair<Int, String?>) = rules.map { FilterRule(it.first, it.second) }.toQueryParams()

    @Test
    fun `multi value rules are joined`() {
        assertEquals(mapOf("tags__id__all" to "1,2", "tags__id__none" to "3"), params(6 to "1", 6 to "2", 17 to "3"))
        assertEquals(mapOf("correspondent__id__in" to "4,5"), params(26 to "4", 26 to "5"))
    }

    @Test
    fun `null and negative values select not assigned and assigned`() {
        assertEquals(mapOf("correspondent__isnull" to "1"), params(3 to null))
        assertEquals(mapOf("document_type__isnull" to "0"), params(4 to "-1"))
        assertEquals(mapOf("storage_path__id" to "7"), params(25 to "7"))
    }

    @Test
    fun `booleans become 1 or 0`() {
        assertEquals(mapOf("is_in_inbox" to "1", "is_tagged" to "0"), params(5 to "true", 7 to "false"))
        assertEquals(mapOf("has_duplicates" to "1"), params(50 to "1"))
    }

    @Test
    fun `title and text rules use the simple search parameters`() {
        assertEquals(mapOf("title_search" to "invoice"), params(0 to "invoice"))
        assertEquals(mapOf("title_search" to "a"), params(48 to "a"))
        assertEquals(mapOf("text" to "renewal"), params(19 to "renewal"))
        assertEquals(mapOf("text" to "b"), params(49 to "b"))
        assertEquals(mapOf("query" to "added:[-1 week to now]"), params(20 to "added:[-1 week to now]"))
    }

    @Test
    fun `dates custom fields and others`() {
        assertEquals(
            mapOf("created__date__gte" to "2026-01-01", "added__date__lt" to "2026-02-01", "created__year" to "2026"),
            params(44 to "2026-01-01", 13 to "2026-02-01", 10 to "2026"),
        )
        assertEquals(
            mapOf("custom_field_query" to """["Due date","exists",true]"""),
            params(
                42 to """["Due date","exists",true]""",
            ),
        )
        assertEquals(
            mapOf("mime_type" to "application/pdf", "owner__id__none" to "2"),
            params(
                47 to "application/pdf",
                35 to "2",
            ),
        )
    }

    @Test
    fun `unknown rules and values without meaning are ignored`() {
        assertEquals(emptyMap<String, String>(), params(999 to "x", 2 to null, 6 to null))
    }
}
