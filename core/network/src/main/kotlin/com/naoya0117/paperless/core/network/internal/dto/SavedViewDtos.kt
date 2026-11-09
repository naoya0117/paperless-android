package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.FilterRule
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.core.model.SavedViewDraft
import com.naoya0117.paperless.core.model.SavedViewVisibility
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** `/api/saved_views/`; API v10 dropped `show_on_dashboard` / `show_in_sidebar` (see UI settings). */
@Serializable
internal data class SavedViewDto(
    val id: Long,
    val name: String,
    val icon: String? = null,
    @SerialName("sort_field") val sortField: String? = null,
    @SerialName("sort_reverse") val sortReverse: Boolean = false,
    @SerialName("filter_rules") val filterRules: List<FilterRuleDto> = emptyList(),
    @SerialName("page_size") val pageSize: Int? = null,
    @SerialName("display_mode") val displayMode: String? = null,
    @SerialName("display_fields") val displayFields: List<String>? = null,
    val owner: Long? = null,
    @SerialName("user_can_change") val userCanChange: Boolean = false,
)

/** `value` is a string or null on the server, but tolerate numbers. */
@Serializable
internal data class FilterRuleDto(
    @SerialName("rule_type") val ruleType: Int,
    val value: JsonElement = JsonNull,
)

internal fun SavedViewDto.toModel() =
    SavedView(
        id = id,
        name = name,
        icon = icon,
        sortField = sortField?.ifEmpty { null },
        sortReverse = sortReverse,
        filterRules = filterRules.map { FilterRule(it.ruleType, (it.value as? JsonPrimitive)?.contentOrNull) },
        pageSize = pageSize,
        displayMode = displayMode,
        displayFields = displayFields,
        owner = owner,
        userCanChange = userCanChange,
    )

internal fun SavedViewDraft.toCreateBody(): JsonObject =
    buildJsonObject {
        put("name", name)
        putFilterRules(filterRules)
        put("sort_field", sortField)
        put("sort_reverse", sortReverse)
        put("page_size", pageSize)
        put("display_mode", displayMode)
        putDisplayFields(displayFields)
        icon?.let { put("icon", it) }
    }

/** Only what differs from [current] (see LabelRequests.kt); filter rules are replaced as a whole. */
internal fun SavedViewDraft.toPatchBody(current: SavedView): JsonObject =
    buildJsonObject {
        if (name != current.name) put("name", name)
        if (filterRules != current.filterRules) putFilterRules(filterRules)
        if (sortField != current.sortField) put("sort_field", sortField)
        if (sortReverse != current.sortReverse) put("sort_reverse", sortReverse)
        if (pageSize != current.pageSize) put("page_size", pageSize)
        if (displayMode != current.displayMode) put("display_mode", displayMode)
        if (displayFields != current.displayFields) putDisplayFields(displayFields)
        // The icon is required on the server (default "funnel"); null in a draft keeps the current one.
        if (icon != null && icon != current.icon) put("icon", icon)
    }

private fun JsonObjectBuilder.putDisplayFields(fields: List<String>?) {
    if (fields == null) {
        put("display_fields", JsonNull)
    } else {
        putJsonArray("display_fields") { fields.forEach { add(JsonPrimitive(it)) } }
    }
}

private fun JsonObjectBuilder.putFilterRules(rules: List<FilterRule>) {
    putJsonArray("filter_rules") {
        rules.forEach { rule ->
            addJsonObject {
                put("rule_type", rule.type)
                put("value", rule.value)
            }
        }
    }
}

/** `ui_settings.settings.saved_views` (API v10). Missing settings mean nothing is shown. */
internal fun JsonObject?.toSavedViewVisibility(): SavedViewVisibility {
    val savedViews = this?.get("saved_views") as? JsonObject

    fun ids(key: String) =
        (savedViews?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    return SavedViewVisibility(ids("dashboard_views_visible_ids"), ids("sidebar_views_visible_ids"))
}
