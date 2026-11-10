package com.naoya0117.paperless.core.database

import com.naoya0117.paperless.core.model.Correspondent
import com.naoya0117.paperless.core.model.CustomField
import com.naoya0117.paperless.core.model.CustomFieldDataType
import com.naoya0117.paperless.core.model.DocumentType
import com.naoya0117.paperless.core.model.FilterRule
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingAlgorithm
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.core.model.SelectOption
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.Tag
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val json = Json { ignoreUnknownKeys = true }

private val longList = ListSerializer(Long.serializer())
private val stringList = ListSerializer(String.serializer())

@Serializable
private data class StoredSelectOption(
    val id: String? = null,
    val label: String,
)

@Serializable
private data class StoredFilterRule(
    val type: Int,
    val value: String? = null,
)

private fun Matching.toColumns() = MatchingColumns(algorithm.id, pattern, isInsensitive)

private fun MatchingColumns.toModel() = Matching(MatchingAlgorithm(algorithm), pattern, isInsensitive)

internal fun Tag.toEntity(accountId: String) =
    TagEntity(
        accountId = accountId,
        id = id,
        name = name,
        slug = slug,
        matching = matching.toColumns(),
        documentCount = documentCount,
        owner = owner,
        userCanChange = userCanChange,
        color = color,
        textColor = textColor,
        isInboxTag = isInboxTag,
        parentId = parentId,
        childIdsJson = json.encodeToString(longList, childIds),
    )

internal fun TagEntity.toModel() =
    Tag(
        id = id,
        name = name,
        slug = slug,
        matching = matching.toModel(),
        documentCount = documentCount,
        owner = owner,
        userCanChange = userCanChange,
        color = color,
        textColor = textColor,
        isInboxTag = isInboxTag,
        parentId = parentId,
        childIds = json.decodeFromString(longList, childIdsJson),
    )

internal fun Correspondent.toEntity(accountId: String) =
    CorrespondentEntity(
        accountId = accountId,
        id = id,
        name = name,
        slug = slug,
        matching = matching.toColumns(),
        documentCount = documentCount,
        owner = owner,
        userCanChange = userCanChange,
        lastCorrespondence = lastCorrespondence?.toString(),
    )

internal fun CorrespondentEntity.toModel() =
    Correspondent(
        id = id,
        name = name,
        slug = slug,
        matching = matching.toModel(),
        documentCount = documentCount,
        owner = owner,
        userCanChange = userCanChange,
        lastCorrespondence = lastCorrespondence?.let(LocalDate::parse),
    )

internal fun DocumentType.toEntity(accountId: String) =
    DocumentTypeEntity(accountId, id, name, slug, matching.toColumns(), documentCount, owner, userCanChange)

internal fun DocumentTypeEntity.toModel() =
    DocumentType(id, name, slug, matching.toModel(), documentCount, owner, userCanChange)

internal fun StoragePath.toEntity(accountId: String) =
    StoragePathEntity(accountId, id, name, slug, matching.toColumns(), documentCount, owner, userCanChange, path)

internal fun StoragePathEntity.toModel() =
    StoragePath(id, name, slug, matching.toModel(), documentCount, owner, userCanChange, path)

internal fun CustomField.toEntity(accountId: String) =
    CustomFieldEntity(
        accountId = accountId,
        id = id,
        name = name,
        dataType = dataType.value,
        selectOptionsJson =
            json.encodeToString(
                ListSerializer(StoredSelectOption.serializer()),
                selectOptions.map { StoredSelectOption(it.id, it.label) },
            ),
        defaultCurrency = defaultCurrency,
        documentCount = documentCount,
    )

internal fun CustomFieldEntity.toModel() =
    CustomField(
        id = id,
        name = name,
        dataType = CustomFieldDataType(dataType),
        selectOptions =
            json
                .decodeFromString(ListSerializer(StoredSelectOption.serializer()), selectOptionsJson)
                .map { SelectOption(it.id, it.label) },
        defaultCurrency = defaultCurrency,
        documentCount = documentCount,
    )

internal fun SavedView.toEntity(accountId: String) =
    SavedViewEntity(
        accountId = accountId,
        id = id,
        name = name,
        icon = icon,
        sortField = sortField,
        sortReverse = sortReverse,
        filterRulesJson =
            json.encodeToString(
                ListSerializer(StoredFilterRule.serializer()),
                filterRules.map { StoredFilterRule(it.type, it.value) },
            ),
        pageSize = pageSize,
        displayMode = displayMode,
        displayFieldsJson = displayFields?.let { json.encodeToString(stringList, it) },
        owner = owner,
        userCanChange = userCanChange,
    )

internal fun SavedViewEntity.toModel() =
    SavedView(
        id = id,
        name = name,
        icon = icon,
        sortField = sortField,
        sortReverse = sortReverse,
        filterRules =
            json
                .decodeFromString(ListSerializer(StoredFilterRule.serializer()), filterRulesJson)
                .map { FilterRule(it.type, it.value) },
        pageSize = pageSize,
        displayMode = displayMode,
        displayFields = displayFieldsJson?.let { json.decodeFromString(stringList, it) },
        owner = owner,
        userCanChange = userCanChange,
    )
