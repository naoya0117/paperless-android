package com.naoya0117.paperless.feature.labels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.data.NotSignedInException
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingLabel
import com.naoya0117.paperless.core.model.Tag
import com.naoya0117.paperless.core.model.descendantsAndSelf
import com.naoya0117.paperless.core.network.PaperlessException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LabelError {
    /** The server rejected values; see [LabelEditState.fieldErrors]. */
    INVALID,
    FORBIDDEN,
    SIGN_IN_REQUIRED,

    /** Offline or the server could not be reached; the input is kept. */
    CONNECTION,
    SERVER,
    OTHER,
}

data class LabelEditState(
    /** `false` while the label being edited is read from the cache. */
    val loaded: Boolean = false,
    /** The label being edited; `null` for a new one. */
    val original: MatchingLabel? = null,
    /** The label is gone (e.g. deleted elsewhere). */
    val missing: Boolean = false,
    val form: LabelForm = LabelForm(),
    /** The server's messages per field (`name`, `match`, `path`, `non_field_errors`, ...). */
    val fieldErrors: Map<String, List<String>> = emptyMap(),
    val saving: Boolean = false,
    val error: LabelError? = null,
    /** Saved or deleted: the screen closes. */
    val done: Boolean = false,
) {
    /** Whether the current user may change it (new labels: yes; the server decides in the end). */
    val editable: Boolean get() = original?.userCanChange ?: true

    val changed: Boolean get() = form != (original?.let(LabelForm::of) ?: LabelForm())

    val canSave: Boolean
        get() = loaded && !missing && editable && !saving && form.name.isNotBlank() && changed && validColor

    /** Only tags have a color; for the others it stays the (valid) default. */
    private val validColor: Boolean get() = isColor(form.color)

    companion object {
        private val COLOR = Regex("#[0-9a-fA-F]{6}")

        fun isColor(value: String): Boolean = COLOR.matches(value)
    }
}

/** Creates a label of a [LabelKind], or edits or deletes one. */
@HiltViewModel
class LabelEditViewModel
    @Inject
    constructor(
        repository: LabelRepository,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        val kind: LabelKind = LabelKind.of(savedState.get<String>(LabelListViewModel.KIND_ARGUMENT))
        private val labelId: Long? = savedState.get<Long>(LABEL_ID_ARGUMENT)?.takeIf { it > 0 }
        private val store = LabelStore(repository, kind)

        private val mutableState = MutableStateFlow(LabelEditState(loaded = labelId == null))
        val state: StateFlow<LabelEditState> = mutableState.asStateFlow()

        /** Tags that can be the parent of this one: not itself or one of its descendants. */
        val parentOptions: StateFlow<List<Tag>> =
            if (kind != LabelKind.TAGS) {
                MutableStateFlow(emptyList())
            } else {
                repository.tags.items
                    .map { tags ->
                        val excluded = labelId?.let { tags.descendantsAndSelf(it) }.orEmpty()
                        tags.filter { it.id !in excluded }.sortedBy { it.name.lowercase() }
                    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())
            }

        /** Tags below this one, which the server deletes with it. */
        val subTagCount: StateFlow<Int> =
            if (kind != LabelKind.TAGS || labelId == null) {
                MutableStateFlow(0)
            } else {
                repository.tags.items
                    .map { it.descendantsAndSelf(labelId).size - 1 }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)
            }

        init {
            if (labelId != null) {
                // The cache may be older than the server (changes are sent against the original), so it is
                // refreshed; the form follows as long as the user did not change it.
                viewModelScope.launch {
                    store.items.collect { items ->
                        val label = items.firstOrNull { it.id == labelId }
                        mutableState.update { it.following(label) }
                    }
                }
                viewModelScope.launch {
                    try {
                        store.refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        // Offline: the cached label is edited.
                    }
                }
            }
        }

        private fun LabelEditState.following(label: MatchingLabel?): LabelEditState =
            when {
                // Saved or deleted here: the cache follows, the screen closes.
                done || saving -> this
                label == null -> copy(loaded = true, missing = true)
                !loaded || !changed ->
                    copy(
                        loaded = true,
                        missing = false,
                        original = label,
                        form = LabelForm.of(label),
                    )
                else -> copy(missing = false, original = label)
            }

        fun setName(name: String) = edit("name") { it.copy(name = name) }

        fun setMatching(matching: Matching) =
            edit("match", "matching_algorithm", "is_insensitive") { it.copy(matching = matching) }

        fun setColor(color: String) = edit("color") { it.copy(color = color) }

        fun setInboxTag(isInboxTag: Boolean) = edit("is_inbox_tag") { it.copy(isInboxTag = isInboxTag) }

        fun setParent(parentId: Long?) = edit("parent") { it.copy(parentId = parentId) }

        fun setPath(path: String) = edit("path") { it.copy(path = path) }

        fun dismissError() = mutableState.update { it.copy(error = null) }

        fun save() {
            val current = mutableState.value
            if (!current.canSave) return
            mutableState.update { it.copy(saving = true, error = null, fieldErrors = emptyMap()) }
            viewModelScope.launch {
                try {
                    store.save(current.original, current.form)
                    mutableState.update { it.copy(saving = false, done = true) }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    mutableState.update { it.failed(e) }
                }
            }
        }

        /** Deletes the label on the server (documents keep existing, without it). */
        fun delete() {
            val current = mutableState.value
            val original = current.original ?: return
            if (current.saving || !current.editable) return
            mutableState.update { it.copy(saving = true, error = null) }
            viewModelScope.launch {
                try {
                    store.delete(original.id)
                    mutableState.update { it.copy(saving = false, done = true) }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    mutableState.update { it.failed(e) }
                }
            }
        }

        private fun LabelEditState.failed(error: Throwable): LabelEditState =
            when (error) {
                is PaperlessException.Http.BadRequest ->
                    copy(saving = false, error = LabelError.INVALID, fieldErrors = error.errors)
                is PaperlessException.Http.Forbidden -> copy(saving = false, error = LabelError.FORBIDDEN)
                is PaperlessException.Http.Unauthorized, is NotSignedInException ->
                    copy(saving = false, error = LabelError.SIGN_IN_REQUIRED)
                is PaperlessException.Network -> copy(saving = false, error = LabelError.CONNECTION)
                is PaperlessException.Http.ServerError -> copy(saving = false, error = LabelError.SERVER)
                else -> copy(saving = false, error = LabelError.OTHER)
            }

        /** Input is ignored while saving; editing a field clears its server error. */
        private fun edit(
            vararg fields: String,
            change: (LabelForm) -> LabelForm,
        ) = mutableState.update { state ->
            if (state.saving || !state.loaded || !state.editable) {
                state
            } else {
                state.copy(
                    form = change(state.form),
                    fieldErrors =
                        state.fieldErrors - fields.toSet() - NON_FIELD_ERRORS,
                )
            }
        }

        companion object {
            const val LABEL_ID_ARGUMENT = "id"
            const val NON_FIELD_ERRORS = "non_field_errors"
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
