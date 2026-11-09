package com.naoya0117.paperless.core.model

/** A change applied to many documents at once (`POST /api/documents/bulk_edit/`). */
sealed interface BulkEdit {
    /** `null` removes the correspondent. */
    data class SetCorrespondent(
        val correspondentId: Long?,
    ) : BulkEdit

    data class SetDocumentType(
        val documentTypeId: Long?,
    ) : BulkEdit

    data class SetStoragePath(
        val storagePathId: Long?,
    ) : BulkEdit

    data class ModifyTags(
        val add: Set<Long> = emptySet(),
        val remove: Set<Long> = emptySet(),
    ) : BulkEdit {
        init {
            require(add.isNotEmpty() || remove.isNotEmpty()) { "Nothing to change" }
            require(add.intersect(remove).isEmpty()) { "A tag cannot be added and removed at once" }
        }
    }

    /** [add] sets the given values (adding the fields where missing); [remove] removes fields. */
    data class ModifyCustomFields(
        val add: List<CustomFieldValue> = emptyList(),
        val remove: Set<Long> = emptySet(),
    ) : BulkEdit {
        init {
            require(add.isNotEmpty() || remove.isNotEmpty()) { "Nothing to change" }
            require(add.map { it.fieldId }.toSet().size == add.size) { "Duplicate custom fields" }
            require(add.none { it.fieldId in remove }) { "A field cannot be added and removed at once" }
        }
    }
}
