package com.naoya0117.paperless.feature.labels

import androidx.annotation.StringRes
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.data.Labels
import com.naoya0117.paperless.core.model.Correspondent
import com.naoya0117.paperless.core.model.CorrespondentDraft
import com.naoya0117.paperless.core.model.DocumentType
import com.naoya0117.paperless.core.model.DocumentTypeDraft
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingLabel
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.StoragePathDraft
import com.naoya0117.paperless.core.model.Tag
import com.naoya0117.paperless.core.model.TagDraft

/** The kinds of labels managed here; [key] is used in navigation routes. */
enum class LabelKind(
    val key: String,
    @StringRes val title: Int,
    @StringRes val newTitle: Int,
    @StringRes val editTitle: Int,
) {
    TAGS("tags", R.string.labels_tags, R.string.labels_new_tag, R.string.labels_edit_tag),
    CORRESPONDENTS(
        "correspondents",
        R.string.labels_correspondents,
        R.string.labels_new_correspondent,
        R.string.labels_edit_correspondent,
    ),
    DOCUMENT_TYPES(
        "document_types",
        R.string.labels_document_types,
        R.string.labels_new_document_type,
        R.string.labels_edit_document_type,
    ),
    STORAGE_PATHS(
        "storage_paths",
        R.string.labels_storage_paths,
        R.string.labels_new_storage_path,
        R.string.labels_edit_storage_path,
    ),
    ;

    companion object {
        fun of(key: String?): LabelKind = entries.firstOrNull { it.key == key } ?: TAGS
    }
}

/** The values being edited, for every kind (fields of other kinds are ignored). */
data class LabelForm(
    val name: String = "",
    val matching: Matching = com.naoya0117.paperless.core.model.DEFAULT_MATCHING,
    /** Tags: `#rrggbb`. */
    val color: String = com.naoya0117.paperless.core.model.DEFAULT_TAG_COLOR,
    val isInboxTag: Boolean = false,
    val parentId: Long? = null,
    /** Storage paths: the path template. */
    val path: String = "",
) {
    companion object {
        fun of(label: MatchingLabel): LabelForm =
            when (label) {
                is Tag ->
                    LabelForm(
                        label.name,
                        label.matching,
                        label.color,
                        label.isInboxTag,
                        label.parentId,
                    )
                is StoragePath -> LabelForm(label.name, label.matching, path = label.path)
                is Correspondent, is DocumentType -> LabelForm(label.name, label.matching)
            }
    }
}

/** The repository operations of one kind, on the common [MatchingLabel] shape. */
internal class LabelStore(
    private val repository: LabelRepository,
    val kind: LabelKind,
) {
    val items =
        when (kind) {
            LabelKind.TAGS -> repository.tags.items
            LabelKind.CORRESPONDENTS -> repository.correspondents.items
            LabelKind.DOCUMENT_TYPES -> repository.documentTypes.items
            LabelKind.STORAGE_PATHS -> repository.storagePaths.items
        }

    private val labels: Labels<*, *> =
        when (kind) {
            LabelKind.TAGS -> repository.tags
            LabelKind.CORRESPONDENTS -> repository.correspondents
            LabelKind.DOCUMENT_TYPES -> repository.documentTypes
            LabelKind.STORAGE_PATHS -> repository.storagePaths
        }

    suspend fun refresh() = labels.refresh()

    suspend fun delete(id: Long) = labels.delete(id)

    /** Creates a label (without [current]) or updates [current]; only changed values are sent. */
    suspend fun save(
        current: MatchingLabel?,
        form: LabelForm,
    ): MatchingLabel {
        val name = form.name.trim()
        return when (kind) {
            LabelKind.TAGS -> {
                val draft = TagDraft(name, form.color, form.matching, form.isInboxTag, form.parentId)
                if (current == null) repository.tags.create(draft) else repository.tags.update(current as Tag, draft)
            }
            LabelKind.CORRESPONDENTS -> {
                val draft = CorrespondentDraft(name, form.matching)
                if (current == null) {
                    repository.correspondents.create(draft)
                } else {
                    repository.correspondents.update(current as Correspondent, draft)
                }
            }
            LabelKind.DOCUMENT_TYPES -> {
                val draft = DocumentTypeDraft(name, form.matching)
                if (current == null) {
                    repository.documentTypes.create(draft)
                } else {
                    repository.documentTypes.update(current as DocumentType, draft)
                }
            }
            LabelKind.STORAGE_PATHS -> {
                val draft = StoragePathDraft(name, form.path.trim(), form.matching)
                if (current == null) {
                    repository.storagePaths.create(draft)
                } else {
                    repository.storagePaths.update(current as StoragePath, draft)
                }
            }
        }
    }
}
