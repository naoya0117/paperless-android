package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.CustomField
import com.naoya0117.paperless.core.model.CustomFieldDataType
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

/** Extra data that does not belong to the data type (the server does not prevent it) is ignored. */
internal fun CustomFieldDto.toModel(): CustomField {
    val type = CustomFieldDataType(dataType)
    return CustomField(
        id = id,
        name = name,
        dataType = type,
        selectOptions =
            if (type == CustomFieldDataType.SELECT) {
                extraData?.selectOptions.orEmpty().map { SelectOption(it.id, it.label) }
            } else {
                emptyList()
            },
        defaultCurrency = extraData?.defaultCurrency?.ifEmpty { null }?.takeIf { type == CustomFieldDataType.MONETARY },
        documentCount = documentCount,
    )
}
