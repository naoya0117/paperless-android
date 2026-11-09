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

    /** Whether the current user may edit or delete the object (`false` if the server did not say). */
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
 * The desired state of a label: everything is sent on create; on update only the properties that differ
 * from the current object are sent (see [LabelApi.update][com.naoya0117.paperless.core.network.LabelApi]),
 * so unchanged values (e.g. the name, which the server re-validates for uniqueness) are left alone.
 * Defaults match the server's defaults for new objects.
 */
data class TagDraft(
    val name: String,
    val color: String = DEFAULT_TAG_COLOR,
    val matching: Matching = DEFAULT_MATCHING,
    val isInboxTag: Boolean = false,
    /** `null` makes the tag a root tag. */
    val parentId: Long? = null,
)

data class CorrespondentDraft(
    val name: String,
    val matching: Matching = DEFAULT_MATCHING,
)

data class DocumentTypeDraft(
    val name: String,
    val matching: Matching = DEFAULT_MATCHING,
)

data class StoragePathDraft(
    val name: String,
    val path: String,
    val matching: Matching = DEFAULT_MATCHING,
)

/** The server's default color for new tags. */
const val DEFAULT_TAG_COLOR: String = "#a6cee3"

/** The server's default matching for new labels. */
val DEFAULT_MATCHING: Matching = Matching(MatchingAlgorithm.ANY)

fun Tag.toDraft(): TagDraft = TagDraft(name, color, matching, isInboxTag, parentId)

fun Correspondent.toDraft(): CorrespondentDraft = CorrespondentDraft(name, matching)

fun DocumentType.toDraft(): DocumentTypeDraft = DocumentTypeDraft(name, matching)

fun StoragePath.toDraft(): StoragePathDraft = StoragePathDraft(name, path, matching)
