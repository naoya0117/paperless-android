package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.DocumentRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.toDraft
import com.naoya0117.paperless.core.network.PaperlessException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** A label to choose from. */
data class LabelOption(
    val id: Long,
    val name: String,
)

/** What can be chosen for the document, from the cached labels. */
data class EditOptions(
    val correspondents: List<LabelOption> = emptyList(),
    val documentTypes: List<LabelOption> = emptyList(),
    val storagePaths: List<LabelOption> = emptyList(),
    val tags: List<TagOption> = emptyList(),
)

data class TagOption(
    val id: Long,
    val label: TagLabel,
)

enum class EditError {
    /** The server rejected values; see [EditForm.fieldErrors]. */
    INVALID,
    FORBIDDEN,

    /** Offline or the server could not be reached; the input is kept. */
    CONNECTION,
    OTHER,
}

/** The values being edited. */
data class EditForm(
    val title: String = "",
    val created: LocalDate? = null,
    val correspondentId: Long? = null,
    val documentTypeId: Long? = null,
    val storagePathId: Long? = null,
    val tagIds: List<Long> = emptyList(),
    /** As typed; must be empty or a whole number. */
    val archiveSerialNumber: String = "",
    /** The server's messages per field (`title`, `created`, `archive_serial_number`, `non_field_errors`, ...). */
    val fieldErrors: Map<String, List<String>> = emptyMap(),
)

data class EditState(
    /** `null` while loading, or if the document could not be loaded ([loadFailed]). */
    val original: Document? = null,
    val form: EditForm = EditForm(),
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val error: EditError? = null,
    /** Saved; the screen closes. */
    val saved: Boolean = false,
) {
    val archiveSerialNumberValid: Boolean
        get() =
            form.archiveSerialNumber.isBlank() ||
                form.archiveSerialNumber
                    .trim()
                    .toLongOrNull()
                    ?.let { it >= 0 } == true

    /** Whether anything differs from the document. */
    val changed: Boolean
        get() =
            original != null &&
                (
                    form.title != original.title ||
                        form.created != original.created ||
                        form.correspondentId != original.correspondentId ||
                        form.documentTypeId != original.documentTypeId ||
                        form.storagePathId != original.storagePathId ||
                        form.tagIds.toSet() != original.tagIds.toSet() ||
                        form.archiveSerialNumber.trim() != (original.archiveSerialNumber?.toString() ?: "")
                )

    val canSave: Boolean
        get() = changed && !saving && form.title.isNotBlank() && form.created != null && archiveSerialNumberValid
}

@HiltViewModel
class DocumentEditViewModel
    @Inject
    constructor(
        private val documents: DocumentRepository,
        labels: LabelRepository,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val documentId: Long = checkNotNull(savedState.get<Long>(DOCUMENT_ID_ARGUMENT)) { "No document ID" }

        private val mutableState = MutableStateFlow(EditState())
        val state: StateFlow<EditState> = mutableState.asStateFlow()

        val options: StateFlow<EditOptions> =
            combine(
                labels.correspondents.items,
                labels.documentTypes.items,
                labels.storagePaths.items,
                labels.tags.items,
            ) {
                correspondents,
                types,
                paths,
                tags,
                ->
                EditOptions(
                    correspondents = correspondents.map { LabelOption(it.id, it.name) },
                    documentTypes = types.map { LabelOption(it.id, it.name) },
                    storagePaths = paths.map { LabelOption(it.id, it.name) },
                    tags = tags.map { TagOption(it.id, TagLabel(it.name, it.color, it.textColor)) },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), EditOptions())

        init {
            load()
        }

        fun retryLoad() = load()

        fun setTitle(value: String) = edit { it.copy(title = value) }

        fun setCreated(value: LocalDate) = edit { it.copy(created = value) }

        fun setCorrespondent(id: Long?) = edit { it.copy(correspondentId = id) }

        fun setDocumentType(id: Long?) = edit { it.copy(documentTypeId = id) }

        fun setStoragePath(id: Long?) = edit { it.copy(storagePathId = id) }

        fun setTags(ids: List<Long>) = edit { it.copy(tagIds = ids.distinct()) }

        fun setArchiveSerialNumber(value: String) = edit { it.copy(archiveSerialNumber = value.filter(Char::isDigit)) }

        fun save() {
            val current = mutableState.value
            val original = current.original ?: return
            if (!current.canSave) return
            val form = current.form
            val draft =
                original.toDraft().copy(
                    title = form.title.trim(),
                    created = checkNotNull(form.created),
                    correspondentId = form.correspondentId,
                    documentTypeId = form.documentTypeId,
                    storagePathId = form.storagePathId,
                    tagIds = form.tagIds,
                    archiveSerialNumber = form.archiveSerialNumber.trim().toLongOrNull(),
                )
            mutableState.value = current.copy(saving = true, error = null, form = form.copy(fieldErrors = emptyMap()))
            viewModelScope.launch {
                val result =
                    try {
                        Result.success(documents.update(original, draft))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        Result.failure(e)
                    }
                mutableState.update { state ->
                    result.fold(
                        onSuccess = { state.copy(saving = false, saved = true) },
                        onFailure = { error ->
                            when (error) {
                                is PaperlessException.Http.BadRequest ->
                                    state.copy(
                                        saving = false,
                                        error = EditError.INVALID,
                                        form = state.form.copy(fieldErrors = error.errors),
                                    )
                                is PaperlessException.Http.Forbidden ->
                                    state.copy(
                                        saving = false,
                                        error = EditError.FORBIDDEN,
                                    )
                                is PaperlessException.Network, is PaperlessException.Http.ServerError ->
                                    state.copy(saving = false, error = EditError.CONNECTION)
                                else -> state.copy(saving = false, error = EditError.OTHER)
                            }
                        },
                    )
                }
            }
        }

        private fun edit(change: (EditForm) -> EditForm) =
            mutableState.update { state ->
                if (state.saving ||
                    state.original == null
                ) {
                    state
                } else {
                    state.copy(form = change(state.form), error = null)
                }
            }

        private fun load() {
            mutableState.update { it.copy(loadFailed = false) }
            viewModelScope.launch {
                val document =
                    try {
                        documents.fetch(documentId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        // Offline: edit the cached copy; saving needs the server anyway.
                        documents.cached(documentId).first()
                    }
                mutableState.update { state ->
                    when {
                        document == null -> state.copy(loadFailed = true)
                        // Keep edits made meanwhile (e.g. a retry after typing).
                        state.original != null -> state
                        else -> state.copy(original = document, form = document.toForm())
                    }
                }
            }
        }

        private fun Document.toForm() =
            EditForm(
                title = title,
                created = created,
                correspondentId = correspondentId,
                documentTypeId = documentTypeId,
                storagePathId = storagePathId,
                tagIds = tagIds,
                archiveSerialNumber = archiveSerialNumber?.toString().orEmpty(),
            )

        companion object {
            const val DOCUMENT_ID_ARGUMENT = "id"
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
