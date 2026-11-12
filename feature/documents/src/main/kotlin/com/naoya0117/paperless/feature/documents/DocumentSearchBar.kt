package com.naoya0117.paperless.feature.documents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naoya0117.paperless.core.model.DateRange
import com.naoya0117.paperless.core.model.DocumentOrdering
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The search field above the documents, round like a launcher's (menu, text, filter button inside its right
 * end), with filter chips below it when the filter button is on.
 *
 * @param focusRequests bump to put the cursor into the field (e.g. when coming from the home screen)
 * @param barModifier for the row of the field (not the filter chips), e.g. to keep it in place between screens
 */
@Composable
fun DocumentSearchBar(
    viewModel: DocumentListViewModel,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    focusRequests: Int = 0,
    barModifier: Modifier = Modifier,
) {
    val text by viewModel.searchText.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val labels by viewModel.labelNames.collectAsStateWithLifecycle()
    var filtersOpen by rememberSaveable { mutableStateOf(!filter.isDefault) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Each request once (not again when the screen comes back, e.g. from a document or after rotation).
    var handledFocus by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(focusRequests) {
        if (focusRequests > handledFocus) {
            handledFocus = focusRequests
            focus.requestFocus()
        }
    }
    Column(Modifier.fillMaxWidth().windowInsetsPadding(BAR_INSETS)) {
        SearchBarRow(navigationIcon, actions, barModifier) {
            TextField(
                value = text,
                onValueChange = viewModel::setSearchText,
                placeholder = { Text(stringResource(R.string.documents_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            viewModel.submitSearch()
                            focusManager.clearFocus()
                        },
                    ),
                colors = transparentFieldColors(),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (text.isNotEmpty()) {
                IconButton(onClick = { viewModel.setSearchText("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
            FilterButton(
                active = !filter.isDefault,
                count = filter.conditionCount,
                open = filtersOpen,
                onClick = { filtersOpen = !filtersOpen },
            )
        }
        AnimatedVisibility(visible = filtersOpen) {
            FilterChips(filter, labels, viewModel::setFilter)
        }
    }
}

/**
 * The same field on the home screen: tapping it goes to the documents to search there.
 *
 * @param barModifier for the row of the field, as in [DocumentSearchBar]
 */
@Composable
fun DocumentSearchButton(
    onClick: () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    barModifier: Modifier = Modifier,
) {
    Column(Modifier.fillMaxWidth().windowInsetsPadding(BAR_INSETS)) {
        SearchBarRow(navigationIcon, actions, barModifier, onClick = onClick) {
            Text(
                stringResource(R.string.documents_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            )
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
    }
}

/** The round field with the menu on its left; [actions] (e.g. the inbox) beside it. */
@Composable
private fun SearchBarRow(
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        val shape = CircleShape
        val color = MaterialTheme.colorScheme.surfaceContainerHigh
        // At least as high as a text field; higher with a large font.
        val size = Modifier.weight(1f).heightIn(min = SEARCH_BAR_HEIGHT)
        val inside: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
                navigationIcon()
                content()
            }
        }
        if (onClick != null) {
            Surface(onClick = onClick, shape = shape, color = color, modifier = size, content = inside)
        } else {
            Surface(shape = shape, color = color, modifier = size, content = inside)
        }
        actions()
    }
}

@Composable
private fun transparentFieldColors() =
    TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )

/** Shows or hides the filter chips; marked while conditions or an order apply. */
@Composable
private fun FilterButton(
    active: Boolean,
    count: Int,
    open: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = stringResource(if (open) R.string.filter_hide else R.string.filter_show),
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class FilterSheet { SORT, TAGS, CORRESPONDENTS, DOCUMENT_TYPES, STORAGE_PATHS, CREATED }

/** A chip per condition (and the order); each opens its choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChips(
    filter: DocumentFilter,
    labels: LabelNames,
    onChange: (DocumentFilter) -> Unit,
) {
    var sheet by rememberSaveable { mutableStateOf<FilterSheet?>(null) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(
                    rememberScrollState(),
                ).padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
    ) {
        FilterChip(
            selected = filter.sort.orderBy != null,
            onClick = { sheet = FilterSheet.SORT },
            label = { Text(sortLabel(filter.sort)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null) },
        )
        CountChip(R.string.filter_tags, filter.tagIds.size) { sheet = FilterSheet.TAGS }
        CountChip(R.string.filter_correspondents, filter.correspondentIds.size) { sheet = FilterSheet.CORRESPONDENTS }
        CountChip(R.string.filter_document_types, filter.documentTypeIds.size) { sheet = FilterSheet.DOCUMENT_TYPES }
        CountChip(R.string.filter_storage_paths, filter.storagePathIds.size) { sheet = FilterSheet.STORAGE_PATHS }
        FilterChip(
            selected = filter.createdRange != null,
            onClick = { sheet = FilterSheet.CREATED },
            label = { Text(createdLabel(filter)) },
        )
        if (!filter.isDefault) {
            AssistChip(
                onClick = { onChange(DocumentFilter()) },
                label = { Text(stringResource(R.string.filter_clear)) },
                leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
            )
        }
    }
    val close = { sheet = null }
    when (sheet) {
        FilterSheet.SORT -> SortSheet(filter.sort, { onChange(filter.copy(sort = it)) }, close)
        FilterSheet.TAGS ->
            LabelSheet(
                title = R.string.filter_tags_title,
                options = remember(labels.tags) { labels.tags.mapValues { it.value.name } },
                selected = filter.tagIds,
                onChange = { onChange(filter.copy(tagIds = it)) },
                onDismiss = close,
            )
        FilterSheet.CORRESPONDENTS ->
            LabelSheet(
                title = R.string.filter_correspondents_title,
                options = labels.correspondents,
                selected = filter.correspondentIds,
                onChange = { onChange(filter.copy(correspondentIds = it)) },
                onDismiss = close,
            )
        FilterSheet.DOCUMENT_TYPES ->
            LabelSheet(
                title = R.string.filter_document_types_title,
                options = labels.documentTypes,
                selected = filter.documentTypeIds,
                onChange = { onChange(filter.copy(documentTypeIds = it)) },
                onDismiss = close,
            )
        FilterSheet.STORAGE_PATHS ->
            LabelSheet(
                title = R.string.filter_storage_paths_title,
                options = labels.storagePaths,
                selected = filter.storagePathIds,
                onChange = { onChange(filter.copy(storagePathIds = it)) },
                onDismiss = close,
            )
        FilterSheet.CREATED ->
            DateSheet(
                filter,
                { created, preset -> onChange(filter.copy(created = created, createdPreset = preset)) },
                close,
            )
        null -> Unit
    }
}

@Composable
private fun CountChip(
    label: Int,
    count: Int,
    onClick: () -> Unit,
) = FilterChip(
    selected = count > 0,
    onClick = onClick,
    label = {
        Text(
            if (count >
                0
            ) {
                stringResource(R.string.filter_count, stringResource(label), count)
            } else {
                stringResource(label)
            },
        )
    },
)

@Composable
private fun fieldName(field: DocumentOrdering.Field): String =
    stringResource(
        when (field) {
            DocumentOrdering.Field.CREATED -> R.string.sort_created
            DocumentOrdering.Field.ADDED -> R.string.sort_added
            DocumentOrdering.Field.MODIFIED -> R.string.sort_modified
            DocumentOrdering.Field.TITLE -> R.string.sort_title
            DocumentOrdering.Field.CORRESPONDENT -> R.string.sort_correspondent
            DocumentOrdering.Field.DOCUMENT_TYPE -> R.string.sort_document_type
            DocumentOrdering.Field.STORAGE_PATH -> R.string.sort_storage_path
            DocumentOrdering.Field.ARCHIVE_SERIAL_NUMBER -> R.string.sort_asn
            DocumentOrdering.Field.NOTE_COUNT -> R.string.sort_notes
            DocumentOrdering.Field.PAGE_COUNT -> R.string.sort_pages
        },
    )

@Composable
private fun sortLabel(sort: DocumentSort): String {
    val field = sort.orderBy ?: return stringResource(R.string.sort_default)
    return fieldName(field) + if (sort.descending) " ↓" else " ↑"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    sort: DocumentSort,
    onChange: (DocumentSort) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    stringResource(R.string.sort_title_sheet),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            item {
                SortRow(stringResource(R.string.sort_default_long), selected = sort.orderBy == null) {
                    onChange(DocumentSort())
                }
            }
            items(DocumentSort.FIELDS) { field ->
                SortRow(fieldName(field), selected = sort.orderBy == field) {
                    // Newest or last first by default, as in the web interface; text fields from A.
                    val descending = field !in TEXT_FIELDS
                    onChange(DocumentSort(field, if (sort.orderBy == field) sort.descending else descending))
                }
            }
            if (sort.orderBy != null) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        FilterChip(
                            selected = !sort.descending,
                            onClick = { onChange(sort.copy(descending = false)) },
                            label = { Text(stringResource(R.string.sort_ascending)) },
                            leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                        )
                        FilterChip(
                            selected = sort.descending,
                            onClick = { onChange(sort.copy(descending = true)) },
                            label = { Text(stringResource(R.string.sort_descending)) },
                            leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                        )
                    }
                }
            }
        }
    }
}

private val TEXT_FIELDS =
    setOf(DocumentOrdering.Field.TITLE, DocumentOrdering.Field.CORRESPONDENT, DocumentOrdering.Field.DOCUMENT_TYPE)

@Composable
private fun SortRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Choosing labels (several), with a field to find them by name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelSheet(
    title: Int,
    options: Map<Long, String>,
    selected: Set<Long>,
    onChange: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Applied when the sheet closes (not a new list per tap).
    var picked by rememberSaveable { mutableStateOf(selected.toList()) }
    // Chosen labels that are not known (e.g. deleted meanwhile) stay visible, so that they can be removed.
    val all = remember(options, selected) { options + (selected - options.keys).associateWith { "#$it" } }
    val shown =
        remember(all, query) {
            all.entries
                .filter { query.isBlank() || it.value.contains(query.trim(), ignoreCase = true) }
                .sortedBy { it.value.lowercase() }
        }
    val close = {
        if (picked.toSet() != selected) onChange(picked.toSet())
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = close) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (picked.isNotEmpty()) {
                TextButton(onClick = { picked = emptyList() }) { Text(stringResource(R.string.filter_clear)) }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.filter_find)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.filter_none_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(shown, key = { it.key }) { (id, name) ->
                val checked = id in picked
                ListItem(
                    headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    modifier = Modifier.clickable { picked = if (checked) picked - id else picked + id },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSheet(
    filter: DocumentFilter,
    onChange: (DateRange?, CreatedPreset?) -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val created = filter.createdRange
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.filter_created_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SortRow(stringResource(R.string.date_any), selected = created == null) {
                onChange(null, null)
                onDismiss()
            }
            CreatedPreset.entries.forEach { preset ->
                SortRow(stringResource(presetName(preset)), selected = filter.createdPreset == preset) {
                    onChange(null, preset)
                    onDismiss()
                }
            }
            SortRow(
                filter.created?.takeIf { filter.createdPreset == null }?.let { dateRangeLabel(it) }
                    ?: stringResource(R.string.date_custom),
                selected = filter.createdPreset == null && filter.created != null,
            ) { picking = true }
        }
    }
    if (picking) {
        val state =
            rememberDateRangePickerState(
                initialSelectedStartDateMillis = created?.from?.toMillis(),
                initialSelectedEndDateMillis = created?.to?.toMillis(),
            )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        picking = false
                        onChange(
                            DateRange(state.selectedStartDateMillis?.toDate(), state.selectedEndDateMillis?.toDate()),
                            null,
                        )
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.date_apply)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { picking = false },
                ) { Text(stringResource(R.string.viewer_cancel)) }
            },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

private fun presetName(preset: CreatedPreset): Int =
    when (preset) {
        CreatedPreset.LAST_30_DAYS -> R.string.date_last_30_days
        CreatedPreset.THIS_YEAR -> R.string.date_this_year
        CreatedPreset.LAST_YEAR -> R.string.date_last_year
    }

@Composable
private fun createdLabel(filter: DocumentFilter): String =
    filter.createdPreset?.let { stringResource(presetName(it)) }
        ?: filter.created?.let { dateRangeLabel(it) }
        ?: stringResource(R.string.filter_created)

/** The pickers work in UTC midnight milliseconds. */
private fun LocalDate.toMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun dateRangeLabel(range: DateRange): String {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val from = range.from?.format(format)
    val to = range.to?.format(format)
    return when {
        from != null && to != null -> stringResource(R.string.date_range, from, to)
        from != null -> stringResource(R.string.date_from, from)
        to != null -> stringResource(R.string.date_until, to)
        else -> stringResource(R.string.filter_created)
    }
}

private val SEARCH_BAR_HEIGHT = 56.dp

/** Clear of the status bar, and of side bars and cutouts (in landscape). */
private val BAR_INSETS: WindowInsets
    @Composable get() =
        WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        )
