package com.naoya0117.paperless.core.model

import java.time.LocalDate

/** Common shape of tags, correspondents, document types and storage paths. */
sealed interface MatchingLabel {
    val id: Long
    val name: String
    val slug: String
    val matching: Matching

    /** Number of documents the current user can see; `null` if the server did not include it. */
    val documentCount: Int?

    /** User ID of the owner; `null` means the object is not owned (visible to everyone). */
    val owner: Long?

    /** Whether the current user may edit or delete the object. */
    val userCanChange: Boolean
}

data class Tag(
    override val id: Long,
    override val name: String,
    override val slug: String,
    override val matching: Matching,
    override val documentCount: Int?,
    override val owner: Long?,
    override val userCanChange: Boolean,
    /** Background color as `#rrggbb`. */
    val color: String,
    /** Text color chosen by the server for contrast with [color]. */
    val textColor: String,
    /** Added to every newly consumed document; documents carrying it make up the inbox. */
    val isInboxTag: Boolean,
    val parentId: Long?,
    val childIds: List<Long>,
) : MatchingLabel

data class Correspondent(
    override val id: Long,
    override val name: String,
    override val slug: String,
    override val matching: Matching,
    override val documentCount: Int?,
    override val owner: Long?,
    override val userCanChange: Boolean,
    /** Date of the newest document from this correspondent, if the server included it. */
    val lastCorrespondence: LocalDate?,
) : MatchingLabel

data class DocumentType(
    override val id: Long,
    override val name: String,
    override val slug: String,
    override val matching: Matching,
    override val documentCount: Int?,
    override val owner: Long?,
    override val userCanChange: Boolean,
) : MatchingLabel

data class StoragePath(
    override val id: Long,
    override val name: String,
    override val slug: String,
    override val matching: Matching,
    override val documentCount: Int?,
    override val owner: Long?,
    override val userCanChange: Boolean,
    /** Jinja template for the file path, e.g. `{{ created_year }}/{{ title }}`. */
    val path: String,
) : MatchingLabel

/**
 * Values to create or update a label with. For optional properties, `null` leaves the choice to the
 * server: its default on create, the current value on update.
 */
data class TagDraft(
    val name: String,
    val color: String? = null,
    val matching: Matching? = null,
    val isInboxTag: Boolean? = null,
    /** Sent as is: `null` makes the tag a root tag. */
    val parentId: Long? = null,
)

data class CorrespondentDraft(
    val name: String,
    val matching: Matching? = null,
)

data class DocumentTypeDraft(
    val name: String,
    val matching: Matching? = null,
)

data class StoragePathDraft(
    val name: String,
    val path: String,
    val matching: Matching? = null,
)
