package com.naoya0117.paperless.feature.labels

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingAlgorithm
import com.naoya0117.paperless.core.model.Tag

/** Creates a label or edits (or deletes) one; closes itself when done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelEditScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LabelEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val parents by viewModel.parentOptions.collectAsStateWithLifecycle()
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(state.done) { if (state.done) currentOnClose() }

    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val close = { if (state.changed && !state.done) confirmDiscard = true else onClose() }
    BackHandler(enabled = state.changed && !state.done) { confirmDiscard = true }

    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        // Field errors are shown at the fields.
        if (error != LabelError.INVALID) {
            snackbar.showSnackbar(resources.getString(error.message()))
            viewModel.dismissError()
        }
    }

    val kind = viewModel.kind
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.original == null) kind.newTitle else kind.editTitle)) },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.labels_close))
                    }
                },
                actions = {
                    if (state.original != null && state.editable) {
                        IconButton(onClick = { confirmDelete = true }, enabled = !state.saving) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.labels_delete))
                        }
                    }
                    if (state.saving) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(onClick = viewModel::save, enabled = state.canSave) {
                            Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.labels_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            !state.loaded ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state.missing ->
                Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.labels_missing))
                }
            else ->
                LabelFields(
                    kind = kind,
                    state = state,
                    parents = parents,
                    viewModel = viewModel,
                    modifier = Modifier.padding(padding),
                )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            text = { Text(stringResource(R.string.labels_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onClose()
                    },
                ) { Text(stringResource(R.string.labels_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.labels_keep_editing)) }
            },
        )
    }
    val original = state.original
    if (confirmDelete && original != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.labels_delete_title)) },
            text = {
                val count = original.documentCount ?: 0
                Text(
                    if (count > 0) {
                        resources.getQuantityString(R.plurals.labels_delete_message_used, count, original.name, count)
                    } else {
                        stringResource(R.string.labels_delete_message, original.name)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                ) { Text(stringResource(R.string.labels_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.labels_cancel)) }
            },
        )
    }
}

@Composable
private fun LabelFields(
    kind: LabelKind,
    state: LabelEditState,
    parents: List<Tag>,
    viewModel: LabelEditViewModel,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val enabled = state.editable && !state.saving
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.editable) {
            Text(
                stringResource(R.string.labels_read_only_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.fieldErrors[LabelEditViewModel.NON_FIELD_ERRORS]?.let { ErrorText(it) }
        OutlinedTextField(
            value = form.name,
            onValueChange = viewModel::setName,
            label = { Text(stringResource(R.string.labels_name)) },
            singleLine = true,
            enabled = enabled,
            isError = "name" in state.fieldErrors,
            supportingText = state.fieldErrors["name"]?.let { { Text(it.joinToString("\n")) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        if (kind == LabelKind.TAGS) {
            ColorField(form.color, enabled, state.fieldErrors["color"], viewModel::setColor)
            SwitchRow(
                text = stringResource(R.string.labels_inbox_tag),
                description = stringResource(R.string.labels_inbox_tag_description),
                checked = form.isInboxTag,
                enabled = enabled,
                onChange = viewModel::setInboxTag,
            )
            ParentField(form.parentId, parents, enabled, state.fieldErrors["parent"], viewModel::setParent)
        }
        if (kind == LabelKind.STORAGE_PATHS) {
            OutlinedTextField(
                value = form.path,
                onValueChange = viewModel::setPath,
                label = { Text(stringResource(R.string.labels_path)) },
                enabled = enabled,
                isError = "path" in state.fieldErrors,
                supportingText = {
                    Text(state.fieldErrors["path"]?.joinToString("\n") ?: stringResource(R.string.labels_path_hint))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        MatchingFields(form.matching, enabled, state.fieldErrors, viewModel::setMatching)
    }
}

@Composable
private fun ErrorText(messages: List<String>) {
    Text(
        messages.joinToString("\n"),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SwitchRow(
    text: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Colors like the Paperless-ngx web interface offers, plus any `#rrggbb`. */
private val TAG_COLORS =
    listOf(
        "#a6cee3",
        "#1f78b4",
        "#b2df8a",
        "#33a02c",
        "#fb9a99",
        "#e31a1c",
        "#fdbf6f",
        "#ff7f00",
        "#cab2d6",
        "#6a3d9a",
        "#b15928",
        "#000000",
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorField(
    color: String,
    enabled: Boolean,
    errors: List<String>?,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.labels_color), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TAG_COLORS.forEach { swatch ->
                val selected = swatch.equals(color, ignoreCase = true)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = enabled) { onChange(swatch) },
                    contentAlignment = Alignment.Center,
                ) {
                    ColorDot(swatch, Modifier.size(if (selected) 40.dp else 32.dp))
                    if (selected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = stringResource(R.string.labels_color_selected),
                            tint =
                                if (isDark(
                                        swatch,
                                    )
                                ) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = color,
            onValueChange = { onChange(it.trim()) },
            label = { Text(stringResource(R.string.labels_color_hex)) },
            leadingIcon = { ColorDot(color) },
            singleLine = true,
            enabled = enabled,
            isError = errors != null || parseColor(color) == null,
            supportingText = errors?.let { { Text(it.joinToString("\n")) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun isDark(hex: String): Boolean {
    val color = parseColor(hex) ?: return false
    return 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue < DARK_LUMINANCE
}

private const val DARK_LUMINANCE = 0.5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentField(
    parentId: Long?,
    parents: List<Tag>,
    enabled: Boolean,
    errors: List<String>?,
    onChange: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(R.string.labels_parent_none)
    val selected = parents.firstOrNull { it.id == parentId }?.name ?: if (parentId == null) none else "#$parentId"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.labels_parent)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            isError = errors != null,
            supportingText = errors?.let { { Text(it.joinToString("\n")) } },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(none) },
                onClick = {
                    onChange(null)
                    expanded = false
                },
            )
            parents.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(tag.name) },
                    leadingIcon = { ColorDot(tag.color, Modifier.size(16.dp)) },
                    onClick = {
                        onChange(tag.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val ALGORITHMS =
    listOf(
        MatchingAlgorithm.NONE,
        MatchingAlgorithm.ANY,
        MatchingAlgorithm.ALL,
        MatchingAlgorithm.LITERAL,
        MatchingAlgorithm.REGEX,
        MatchingAlgorithm.FUZZY,
        MatchingAlgorithm.AUTO,
    )

/** Whether the algorithm uses the pattern (and case sensitivity). */
private val MatchingAlgorithm.usesPattern: Boolean get() = id in MatchingAlgorithm.ANY.id..MatchingAlgorithm.FUZZY.id

@Composable
private fun algorithmName(algorithm: MatchingAlgorithm): String =
    when (algorithm) {
        MatchingAlgorithm.NONE -> stringResource(R.string.labels_matching_none)
        MatchingAlgorithm.ANY -> stringResource(R.string.labels_matching_any)
        MatchingAlgorithm.ALL -> stringResource(R.string.labels_matching_all)
        MatchingAlgorithm.LITERAL -> stringResource(R.string.labels_matching_literal)
        MatchingAlgorithm.REGEX -> stringResource(R.string.labels_matching_regex)
        MatchingAlgorithm.FUZZY -> stringResource(R.string.labels_matching_fuzzy)
        MatchingAlgorithm.AUTO -> stringResource(R.string.labels_matching_auto)
        else -> stringResource(R.string.labels_matching_other, algorithm.id)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchingFields(
    matching: Matching,
    enabled: Boolean,
    fieldErrors: Map<String, List<String>>,
    onChange: (Matching) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
            OutlinedTextField(
                value = algorithmName(matching.algorithm),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.labels_matching)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                isError = "matching_algorithm" in fieldErrors,
                supportingText = {
                    Text(
                        fieldErrors["matching_algorithm"]?.joinToString("\n")
                            ?: stringResource(R.string.labels_matching_hint),
                    )
                },
                modifier =
                    Modifier.fillMaxWidth().menuAnchor(
                        ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                        enabled,
                    ),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ALGORITHMS.forEach { algorithm ->
                    DropdownMenuItem(
                        text = { Text(algorithmName(algorithm)) },
                        onClick = {
                            onChange(matching.copy(algorithm = algorithm))
                            expanded = false
                        },
                    )
                }
            }
        }
        if (matching.algorithm.usesPattern) {
            OutlinedTextField(
                value = matching.pattern,
                onValueChange = { onChange(matching.copy(pattern = it)) },
                label = { Text(stringResource(R.string.labels_matching_pattern)) },
                enabled = enabled,
                isError = "match" in fieldErrors,
                supportingText = fieldErrors["match"]?.let { { Text(it.joinToString("\n")) } },
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                text = stringResource(R.string.labels_matching_insensitive),
                description = null,
                checked = matching.isInsensitive,
                enabled = enabled,
                onChange = { onChange(matching.copy(isInsensitive = it)) },
            )
        }
    }
}

private fun LabelError.message(): Int =
    when (this) {
        LabelError.INVALID -> R.string.labels_error_invalid
        LabelError.FORBIDDEN -> R.string.labels_error_forbidden
        LabelError.SIGN_IN_REQUIRED -> R.string.labels_error_sign_in
        LabelError.CONNECTION -> R.string.labels_error_connection
        LabelError.SERVER -> R.string.labels_error_server
        LabelError.OTHER -> R.string.labels_error_other
    }
