package com.naoya0117.paperless.core.model

/**
 * A document list saved in Paperless (e.g. from the web UI). Its filter is a list of [FilterRule]s, kept as
 * stored so that views can be shown and written back without loss.
 */
data class SavedView(
    val id: Long,
    val name: String,
    val icon: String?,
    /** Server field to order by (e.g. `created`, `title`, `custom_field_3`); `null` for the default. */
    val sortField: String?,
    val sortReverse: Boolean,
    val filterRules: List<FilterRule>,
    val pageSize: Int?,
    /** Web UI display mode, e.g. `table`, `smallCards`, `largeCards`. */
    val displayMode: String?,
    val displayFields: List<String>,
    val owner: Long?,
    val userCanChange: Boolean,
)

/**
 * One condition of a saved view. [type] is a Paperless filter rule type (e.g. 6 = has tag); see
 * `FilterRuleType`. [value] is `null` for "not assigned" rules.
 */
data class FilterRule(
    val type: Int,
    val value: String?,
)

/** The desired state of a saved view (see [TagDraft] for how create and update use drafts). */
data class SavedViewDraft(
    val name: String,
    val filterRules: List<FilterRule> = emptyList(),
    val sortField: String? = "created",
    val sortReverse: Boolean = true,
    val pageSize: Int? = null,
    val displayMode: String? = null,
    val displayFields: List<String> = emptyList(),
    val icon: String? = null,
)

fun SavedView.toDraft(): SavedViewDraft =
    SavedViewDraft(name, filterRules, sortField, sortReverse, pageSize, displayMode, displayFields, icon)

/** Which saved views the current user shows on the dashboard and in the sidebar (stored in UI settings). */
data class SavedViewVisibility(
    val dashboardViewIds: List<Long>,
    val sidebarViewIds: List<Long>,
)
