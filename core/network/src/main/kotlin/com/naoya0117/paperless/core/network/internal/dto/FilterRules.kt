package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.FilterRule

/**
 * Converts saved view filter rules to document list query parameters, following the web UI
 * (`src-ui/src/app/utils/query-params.ts` `queryParamsFromFilterRules` and `data/filter-rule-type.ts`).
 * Unknown rule types are ignored.
 */
internal fun List<FilterRule>.toQueryParams(): Map<String, String> {
    val params = linkedMapOf<String, String>()
    for (rule in this) {
        val type = FILTER_RULE_TYPES[rule.type] ?: continue
        val value = rule.value
        when {
            rule.type == TITLE_CONTENT || rule.type == SIMPLE_TEXT -> value?.let { params[TEXT] = it }
            rule.type == TITLE || rule.type == SIMPLE_TITLE -> value?.let { params[TITLE_SEARCH] = it }
            type.isNullParam != null && value == null -> params[type.isNullParam] = "1"
            type.isNullParam != null && value == NEGATIVE_NULL_FILTER_VALUE -> params[type.isNullParam] = "0"
            value == null -> Unit
            type.multi -> params[type.param] = params[type.param]?.let { "$it,$value" } ?: value
            type.boolean -> params[type.param] = if (value == "true" || value == "1") "1" else "0"
            else -> params[type.param] = value
        }
    }
    return params
}

private class FilterRuleType(
    val param: String,
    val multi: Boolean = false,
    val boolean: Boolean = false,
    val isNullParam: String? = null,
)

private const val TITLE = 0
private const val TITLE_CONTENT = 19
private const val SIMPLE_TITLE = 48
private const val SIMPLE_TEXT = 49
private const val TEXT = "text"
private const val TITLE_SEARCH = "title_search"
private const val NEGATIVE_NULL_FILTER_VALUE = "-1"

private val FILTER_RULE_TYPES: Map<Int, FilterRuleType> =
    mapOf(
        0 to FilterRuleType("title__icontains"),
        48 to FilterRuleType(TITLE_SEARCH),
        1 to FilterRuleType("content__icontains"),
        2 to FilterRuleType("archive_serial_number"),
        3 to FilterRuleType("correspondent__id", isNullParam = "correspondent__isnull"),
        26 to FilterRuleType("correspondent__id__in", multi = true),
        27 to FilterRuleType("correspondent__id__none", multi = true),
        25 to FilterRuleType("storage_path__id", isNullParam = "storage_path__isnull"),
        30 to FilterRuleType("storage_path__id__in", multi = true),
        31 to FilterRuleType("storage_path__id__none", multi = true),
        4 to FilterRuleType("document_type__id", isNullParam = "document_type__isnull"),
        28 to FilterRuleType("document_type__id__in", multi = true),
        29 to FilterRuleType("document_type__id__none", multi = true),
        5 to FilterRuleType("is_in_inbox", boolean = true),
        6 to FilterRuleType("tags__id__all", multi = true),
        22 to FilterRuleType("tags__id__in", multi = true),
        17 to FilterRuleType("tags__id__none", multi = true),
        7 to FilterRuleType("is_tagged", boolean = true),
        8 to FilterRuleType("created__date__lt"),
        9 to FilterRuleType("created__date__gt"),
        43 to FilterRuleType("created__date__lte"),
        44 to FilterRuleType("created__date__gte"),
        10 to FilterRuleType("created__year"),
        11 to FilterRuleType("created__month"),
        12 to FilterRuleType("created__day"),
        13 to FilterRuleType("added__date__lt"),
        14 to FilterRuleType("added__date__gt"),
        45 to FilterRuleType("added__date__lte"),
        46 to FilterRuleType("added__date__gte"),
        15 to FilterRuleType("modified__date__lt"),
        16 to FilterRuleType("modified__date__gt"),
        18 to FilterRuleType("archive_serial_number__isnull", boolean = true),
        23 to FilterRuleType("archive_serial_number__gt"),
        24 to FilterRuleType("archive_serial_number__lt"),
        19 to FilterRuleType(TEXT),
        49 to FilterRuleType(TEXT),
        20 to FilterRuleType("query"),
        21 to FilterRuleType("more_like_id"),
        32 to FilterRuleType("owner__id"),
        33 to FilterRuleType("owner__id__in", multi = true),
        34 to FilterRuleType("owner__isnull", boolean = true),
        35 to FilterRuleType("owner__id__none", multi = true),
        37 to FilterRuleType("shared_by__id", multi = true),
        36 to FilterRuleType("custom_fields__icontains"),
        38 to FilterRuleType("custom_fields__id__all", multi = true),
        39 to FilterRuleType("custom_fields__id__in", multi = true),
        40 to FilterRuleType("custom_fields__id__none", multi = true),
        41 to FilterRuleType("has_custom_fields", boolean = true),
        42 to FilterRuleType("custom_field_query"),
        47 to FilterRuleType("mime_type"),
        50 to FilterRuleType("has_duplicates", boolean = true),
    )
