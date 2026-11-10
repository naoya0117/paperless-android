package com.naoya0117.paperless.feature.documents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Editing a document's title, date, correspondent, type, storage path, tags and archive serial number.
 *
 * @param onClose leaves the editor (after saving, or discarding the changes)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentEditScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()

    // Leaves once, whichever way (saved, or discarded).
    var closed by remember { mutableStateOf(false) }
    val leave = {
        if (!closed) {
            closed = true
            onClose()
        }
    }
    LaunchedEffect(state.saved) { if (state.saved) leave() }

    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val close = {
        when {
            // A save in progress must finish (the server may already have applied it).
            state.saving || state.saved -> Unit
            state.changed -> confirmDiscard = true
            else -> leave()
        }
    }
    BackHandler(enabled = state.saving || state.changed) { close() }
    if (confirmDiscard) {
        DiscardDialog(onDiscard = {
            confirmDiscard = false
            leave()
        }, onKeep = { confirmDiscard = false })
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_title)) },
                navigationIcon = {
                    IconButton(onClick = close, enabled = !state.saving) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.edit_close))
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(
                            onClick = viewModel::save,
                            enabled = state.canSave,
                        ) { Text(stringResource(R.string.edit_save)) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val loadError = state.loadError
            when {
                state.original != null -> EditForm(state, options, viewModel)
                loadError != null ->
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        val message =
                            when (loadError) {
                                EditLoadError.NOT_FOUND -> R.string.edit_load_not_found
                                EditLoadError.FORBIDDEN -> R.string.edit_error_forbidden
                                EditLoadError.CONNECTION, EditLoadError.OTHER -> R.string.viewer_failed
                            }
                        Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = viewModel::retryLoad) { Text(stringResource(R.string.documents_retry)) }
                    }
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun DiscardDialog(
    onDiscard: () -> Unit,
    onKeep: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.edit_discard_title)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.edit_discard)) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.edit_keep_editing)) } },
    )
}

@Composable
private fun EditForm(
    state: EditState,
    options: EditOptions,
    viewModel: DocumentEditViewModel,
) {
    val form = state.form
    val errors = form.fieldErrors
    val enabled = !state.saving
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { ErrorMessage(it, errors) }
        OutlinedTextField(
            value = form.title,
            onValueChange = viewModel::setTitle,
            label = { Text(stringResource(R.string.edit_field_title)) },
            isError = form.title.isBlank() || errors.containsKey("title"),
            supportingText = errors["title"]?.let { { Text(it.joinToString(" ")) } },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField(form.created, errors["created"], enabled, viewModel::setCreated)
        SelectField(
            label = stringResource(R.string.info_correspondent),
            errors = errors["correspondent"],
            options = options.correspondents,
            selectedId = form.correspondentId,
            enabled = enabled,
            onSelect = viewModel::setCorrespondent,
        )
        SelectField(
            label = stringResource(R.string.info_document_type),
            errors = errors["document_type"],
            options = options.documentTypes,
            selectedId = form.documentTypeId,
            enabled = enabled,
            onSelect = viewModel::setDocumentType,
        )
        SelectField(
            label = stringResource(R.string.info_storage_path),
            errors = errors["storage_path"],
            options = options.storagePaths,
            selectedId = form.storagePathId,
            enabled = enabled,
            onSelect = viewModel::setStoragePath,
        )
        TagsField(form.tagIds, options.tags, errors["tags"], enabled, viewModel::setTags)
        OutlinedTextField(
            value = form.archiveSerialNumber,
            onValueChange = viewModel::setArchiveSerialNumber,
            label = { Text(stringResource(R.string.edit_field_archive_serial_number)) },
            isError = !state.archiveSerialNumberValid || errors.containsKey("archive_serial_number"),
            supportingText = errors["archive_serial_number"]?.let { { Text(it.joinToString(" ")) } },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Fields whose server messages are shown at the field; the others (e.g. `non_field_errors`) at the top. */
private val FIELDS_WITH_OWN_ERRORS =
    setOf("title", "created", "correspondent", "document_type", "storage_path", "tags", "archive_serial_number")

@Composable
private fun ErrorMessage(
    error: EditError,
    fieldErrors: Map<String, List<String>>,
) {
    val text =
        when (error) {
            EditError.INVALID -> stringResource(R.string.edit_error_invalid)
            EditError.FORBIDDEN -> stringResource(R.string.edit_error_forbidden)
            EditError.SIGN_IN_REQUIRED -> stringResource(R.string.edit_error_sign_in)
            EditError.CONFLICT -> stringResource(R.string.edit_error_conflict)
            EditError.CONNECTION -> stringResource(R.string.edit_error_connection)
            EditError.SERVER -> stringResource(R.string.edit_error_server)
            EditError.OTHER -> stringResource(R.string.edit_error_other)
        }
    Column {
        Text(text, color = MaterialTheme.colorScheme.error)
        fieldErrors
            .filterKeys { it !in FIELDS_WITH_OWN_ERRORS }
            .values
            .flatten()
            .forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** A read-only field that opens a picker when tapped. */
@Composable
private fun PickerField(
    label: String,
    value: String,
    enabled: Boolean,
    errors: List<String>? = null,
    onClick: () -> Unit,
) {
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = errors != null,
            supportingText = errors?.let { { Text(it.joinToString(" ")) } },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        // One button for accessibility services (the read-only field itself does nothing when activated).
        Box(
            Modifier
                .matchParentSize()
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = "$label: $value"
                }.clickable(enabled = enabled, onClick = onClick),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    value: LocalDate?,
    errors: List<String>?,
    enabled: Boolean,
    onChange: (LocalDate) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    PickerField(
        label = stringResource(R.string.info_created),
        value = value?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)).orEmpty(),
        enabled = enabled,
        errors = errors,
        onClick = { open = true },
    )
    if (open) {
        // The picker works in UTC milliseconds.
        val initial = value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        onChange(
                            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                    open = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { open = false },
                ) { Text(stringResource(android.R.string.cancel)) }
            },
        ) {
            DatePicker(state = picker)
        }
    }
}

/** One of [options] or none, chosen in a searchable dialog. */
@Composable
private fun SelectField(
    label: String,
    errors: List<String>?,
    options: List<LabelOption>,
    selectedId: Long?,
    enabled: Boolean,
    onSelect: (Long?) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val none = stringResource(R.string.edit_none)
    PickerField(
        label = label,
        value = selectedId?.let { id -> options.firstOrNull { it.id == id }?.name ?: "#$id" } ?: none,
        enabled = enabled,
        errors = errors,
        onClick = { open = true },
    )
    if (open) {
        SearchDialog(title = label, onDismiss = { open = false }) { query ->
            item {
                ChoiceRow(none, selected = selectedId == null) {
                    onSelect(null)
                    open = false
                }
            }
            items(options.filter { it.name.contains(query, ignoreCase = true) }, key = { it.id }) { option ->
                ChoiceRow(option.name, selected = option.id == selectedId) {
                    onSelect(option.id)
                    open = false
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsField(
    tagIds: List<Long>,
    options: List<TagOption>,
    errors: List<String>?,
    enabled: Boolean,
    onChange: (List<Long>) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val byId = options.associateBy { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.edit_field_tags), style = MaterialTheme.typography.labelLarge)
        errors?.let {
            Text(
                it.joinToString(" "),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tagIds.forEach { id ->
                val name = byId[id]?.label?.name ?: "#$id"
                InputChip(
                    selected = false,
                    enabled = enabled,
                    onClick = { onChange(tagIds - id) },
                    label = { Text(name) },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.edit_remove_tag, name),
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
            InputChip(
                selected = false,
                enabled = enabled,
                onClick = { open = true },
                label = { Text(stringResource(R.string.edit_add_tags)) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
    if (open) {
        // The picks apply when confirmed, and survive rotation meanwhile.
        val selected =
            rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
                tagIds.toMutableStateList()
            }
        SearchDialog(
            title = stringResource(R.string.edit_field_tags),
            onDismiss = { open = false },
            onConfirm = {
                onChange(selected.toList())
                open = false
            },
        ) { query ->
            items(options.filter { it.label.name.contains(query, ignoreCase = true) }, key = { it.id }) { option ->
                val checked = option.id in selected
                val toggle = { if (checked) selected.remove(option.id) else selected.add(option.id) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                    TagChip(option.label)
                }
            }
        }
    }
}

/** A dialog with a search field over a list; with [onConfirm] it has OK / Cancel buttons. */
@Composable
private fun SearchDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)? = null,
    content: LazyListScope.(query: String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.edit_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) { content(query.trim()) }
            }
        },
        confirmButton = {
            if (onConfirm != null) {
                TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
