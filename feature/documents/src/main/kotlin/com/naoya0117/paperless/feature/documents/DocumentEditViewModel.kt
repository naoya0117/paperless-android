package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.DocumentRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.data.NotSignedInException
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.toDraft
import com.naoya0117.paperless.core.network.PaperlessException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** The account must sign in again. */
    SIGN_IN_REQUIRED,

    /**
     * The document was changed on the server since it was opened (from an offline copy); nothing was saved.
     * The form now compares against the current version; saving again applies the edits to it.
     */
    CONFLICT,

    /** Offline or the server could not be reached; the input is kept. */
    CONNECTION,

    /** The server failed (5xx); the input is kept. */
    SERVER,
    OTHER,
}

/** Why the document could not be opened for editing. */
enum class EditLoadError { NOT_FOUND, FORBIDDEN, CONNECTION, OTHER }

/** The values being edited. */
data class EditForm(
    val title: String = "",
    val created: LocalDate? = null,
    val correspondentId: Long? = null,
    val documentTypeId: Long? = null,
    val storagePathId: Long? = null,
    val tagIds: List<Long> = emptyList(),
    /** As typed (digits only). */
    val archiveSerialNumber: String = "",
    /** The server's messages per field (`title`, `tags`, `non_field_errors`, ...), cleared when a field is edited. */
    val fieldErrors: Map<String, List<String>> = emptyMap(),
)

data class EditState(
    /** `null` while loading, or if the document could not be loaded ([loadError]). */
    val original: Document? = null,
    /** Whether [original] came from the server; an offline copy is checked against the server before saving. */
    val originalFromServer: Boolean = false,
    val form: EditForm = EditForm(),
    val loadError: EditLoadError? = null,
    val saving: Boolean = false,
    val error: EditError? = null,
    /** Saved; the screen closes. */
    val saved: Boolean = false,
) {
    val archiveSerialNumberValid: Boolean
        get() = form.archiveSerialNumber.isEmpty() || form.archiveSerialNumber.toLongOrNull() != null

    /** Whether anything differs from the document, as it would be sent. */
    val changed: Boolean
        get() =
            original != null &&
                (
                    form.title.trim() != original.title ||
                        form.created != original.created ||
                        form.correspondentId != original.correspondentId ||
                        form.documentTypeId != original.documentTypeId ||
                        form.storagePathId != original.storagePathId ||
                        form.tagIds.toSet() != original.tagIds.toSet() ||
                        form.archiveSerialNumber.toLongOrNull() != original.archiveSerialNumber
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
        private val savedState: SavedStateHandle,
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

        fun setTitle(value: String) = edit("title") { it.copy(title = value) }

        fun setCreated(value: LocalDate) = edit("created") { it.copy(created = value) }

        fun setCorrespondent(id: Long?) = edit("correspondent") { it.copy(correspondentId = id) }

        fun setDocumentType(id: Long?) = edit("document_type") { it.copy(documentTypeId = id) }

        fun setStoragePath(id: Long?) = edit("storage_path") { it.copy(storagePathId = id) }

        fun setTags(ids: List<Long>) = edit("tags") { it.copy(tagIds = ids.distinct()) }

        fun setArchiveSerialNumber(value: String) =
            edit("archive_serial_number") { it.copy(archiveSerialNumber = value.filter(Char::isDigit)) }

        /**
         * Saves the changes. Not cancellable once started (the server may apply it at any moment, and the
         * cache must follow); the screen does not let the user leave meanwhile.
         */
        fun save() {
            val current = mutableState.value
            if (current.original == null || !current.canSave) return
            mutableState.value =
                current.copy(saving = true, error = null, form = current.form.copy(fieldErrors = emptyMap()))
            viewModelScope.launch {
                val result =
                    withContext(NonCancellable) {
                        try {
                            Result.success(saveChecked(current))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Exception,
                        ) {
                            Result.failure(e)
                        }
                    }
                mutableState.update { state ->
                    result.fold(
                        onSuccess = { fresh ->
                            if (fresh == null) {
                                state.copy(saving = false, saved = true)
                            } else {
                                // Changed on the server: compare against the current version from now on.
                                state.copy(
                                    saving = false,
                                    original = fresh,
                                    originalFromServer = true,
                                    error = EditError.CONFLICT,
                                )
                            }
                        },
                        onFailure = { error -> state.failed(error) },
                    )
                }
                if (mutableState.value.saved) savedState.remove<Bundle?>(FORM_KEY)
            }
        }

        /** @return `null` when saved, or the server's newer version when the offline copy was outdated */
        private suspend fun saveChecked(state: EditState): Document? {
            var original = checkNotNull(state.original)
            if (!state.originalFromServer) {
                val fresh = documents.fetch(documentId)
                if (fresh.modified != original.modified) return fresh
                original = fresh
            }
            val form = state.form
            val draft =
                original.toDraft().copy(
                    title = form.title.trim(),
                    created = checkNotNull(form.created),
                    correspondentId = form.correspondentId,
                    documentTypeId = form.documentTypeId,
                    storagePathId = form.storagePathId,
                    tagIds = form.tagIds,
                    archiveSerialNumber = form.archiveSerialNumber.toLongOrNull(),
                )
            documents.update(original, draft)
            return null
        }

        private fun EditState.failed(error: Throwable): EditState =
            when (error) {
                is PaperlessException.Http.BadRequest ->
                    copy(saving = false, error = EditError.INVALID, form = form.copy(fieldErrors = error.errors))
                is PaperlessException.Http.Forbidden -> copy(saving = false, error = EditError.FORBIDDEN)
                is PaperlessException.Http.Unauthorized, is NotSignedInException ->
                    copy(saving = false, error = EditError.SIGN_IN_REQUIRED)
                is PaperlessException.Network -> copy(saving = false, error = EditError.CONNECTION)
                is PaperlessException.Http.ServerError -> copy(saving = false, error = EditError.SERVER)
                else -> copy(saving = false, error = EditError.OTHER)
            }

        /** Input is ignored while saving; editing a field clears its server error. */
        private fun edit(
            field: String,
            change: (EditForm) -> EditForm,
        ) {
            mutableState.update { state ->
                if (state.saving || state.original == null) {
                    state
                } else {
                    val changed = change(state.form)
                    state.copy(
                        form = changed.copy(fieldErrors = changed.fieldErrors - field - "non_field_errors"),
                        error = null,
                    )
                }
            }
            saveForm()
        }

        private fun load() {
            mutableState.update { it.copy(loadError = null) }
            viewModelScope.launch {
                val loaded =
                    try {
                        documents.fetch(documentId) to true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PaperlessException) {
                        // Offline or the server is down: edit the cached copy (checked against the server before
                        // saving); anything else (e.g. deleted, not allowed) is a load error.
                        if (e !is PaperlessException.Network && e !is PaperlessException.Http.ServerError) {
                            return@launch failLoad(e.loadError())
                        }
                        documents.cached(documentId).first()?.let { it to false }
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        return@launch failLoad(EditLoadError.OTHER)
                    }
                if (loaded == null) return@launch failLoad(EditLoadError.CONNECTION)
                val (document, fromServer) = loaded
                mutableState.update { state ->
                    // Keep edits made meanwhile (a retry), else bring back those saved before the process died.
                    if (state.original != null) {
                        state
                    } else {
                        state.copy(
                            original = document,
                            originalFromServer = fromServer,
                            form =
                                restoredForm() ?: document.toForm(),
                        )
                    }
                }
            }
        }

        private fun failLoad(error: EditLoadError) = mutableState.update { it.copy(loadError = error) }

        private fun PaperlessException.loadError(): EditLoadError =
            when (this) {
                is PaperlessException.Http.NotFound -> EditLoadError.NOT_FOUND
                is PaperlessException.Http.Forbidden -> EditLoadError.FORBIDDEN
                else -> EditLoadError.OTHER
            }

        /** Keeps the edits across process death (not the server's error messages). */
        private fun saveForm() {
            val form = mutableState.value.form
            savedState[FORM_KEY] =
                Bundle(
                    form.title,
                    form.created?.toEpochDay(),
                    form.correspondentId,
                    form.documentTypeId,
                    form.storagePathId,
                    ArrayList(form.tagIds),
                    form.archiveSerialNumber,
                ).toMap()
        }

        private fun restoredForm(): EditForm? =
            savedState.get<HashMap<String, Any?>>(FORM_KEY)?.let { map ->
                @Suppress("UNCHECKED_CAST")
                EditForm(
                    title = map["title"] as? String ?: return null,
                    created = (map["created"] as? Long)?.let(LocalDate::ofEpochDay),
                    correspondentId = map["correspondent"] as? Long,
                    documentTypeId = map["documentType"] as? Long,
                    storagePathId = map["storagePath"] as? Long,
                    tagIds = (map["tags"] as? ArrayList<Long>).orEmpty(),
                    archiveSerialNumber = map["asn"] as? String ?: "",
                )
            }

        /** The saved form, as a map of simple values (saved state only takes such). */
        private class Bundle(
            val title: String,
            val created: Long?,
            val correspondent: Long?,
            val documentType: Long?,
            val storagePath: Long?,
            val tags: ArrayList<Long>,
            val asn: String,
        ) {
            fun toMap(): HashMap<String, Any?> =
                hashMapOf(
                    "title" to title,
                    "created" to created,
                    "correspondent" to correspondent,
                    "documentType" to documentType,
                    "storagePath" to storagePath,
                    "tags" to tags,
                    "asn" to asn,
                )
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
            private const val FORM_KEY = "edit.form"
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
