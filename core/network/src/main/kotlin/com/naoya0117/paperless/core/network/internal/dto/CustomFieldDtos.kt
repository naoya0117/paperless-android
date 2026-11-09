package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.CustomField
import com.naoya0117.paperless.core.model.CustomFieldDataType
import com.naoya0117.paperless.core.model.CustomFieldDraft
import com.naoya0117.paperless.core.model.SelectOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `/api/custom_fields/` */
@Serializable
internal data class CustomFieldDto(
    val id: Long,
    val name: String,
    @SerialName("data_type") val dataType: String,
    @SerialName("extra_data") val extraData: ExtraDataDto? = null,
    @SerialName("document_count") val documentCount: Int? = null,
)

/** API v7+: select options are `{id, label}` objects; documents store the option ID. */
@Serializable
internal data class ExtraDataDto(
    @SerialName("select_options") val selectOptions: List<SelectOptionDto>? = null,
    @SerialName("default_currency") val defaultCurrency: String? = null,
)

@Serializable
internal data class SelectOptionDto(
    val id: String? = null,
    val label: String,
)

internal fun CustomFieldDto.toModel() =
    CustomField(
        id = id,
        name = name,
        dataType = CustomFieldDataType(dataType),
        selectOptions =
            extraData
                ?.selectOptions
                .orEmpty()
                .mapNotNull { option -> option.id?.let { SelectOption(it, option.label) } },
        defaultCurrency = extraData?.defaultCurrency?.ifEmpty { null },
        documentCount = documentCount,
    )

/** `data_type` is only sent on create (null on update): the server does not allow changing it. */
@Serializable
internal data class CustomFieldWriteDto(
    val name: String,
    @SerialName("data_type") val dataType: String? = null,
    @SerialName("extra_data") val extraData: ExtraDataDto? = null,
)

internal fun CustomFieldDraft.toDto(includeDataType: Boolean) =
    CustomFieldWriteDto(
        name = name,
        dataType = if (includeDataType) dataType.value else null,
        extraData =
            if (selectOptions == null && defaultCurrency == null) {
                null
            } else {
                ExtraDataDto(
                    selectOptions = selectOptions?.map { SelectOptionDto(it.id, it.label) },
                    defaultCurrency = defaultCurrency,
                )
            },
    )
