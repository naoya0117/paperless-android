package com.naoya0117.paperless.core.model

/** Data type of a custom field; a value class so that types added by newer servers can still be read. */
@JvmInline
value class CustomFieldDataType(
    val value: String,
) {
    companion object {
        val STRING = CustomFieldDataType("string")
        val LONG_TEXT = CustomFieldDataType("longtext")
        val URL = CustomFieldDataType("url")
        val DATE = CustomFieldDataType("date")
        val BOOLEAN = CustomFieldDataType("boolean")
        val INTEGER = CustomFieldDataType("integer")
        val FLOAT = CustomFieldDataType("float")
        val MONETARY = CustomFieldDataType("monetary")
        val DOCUMENT_LINK = CustomFieldDataType("documentlink")
        val SELECT = CustomFieldDataType("select")
    }
}

/**
 * An option of a [CustomFieldDataType.SELECT] field; documents store the [id]. [id] is `null` only for
 * options the server stored without one (legacy data); they are kept so that an update does not drop them.
 */
data class SelectOption(
    val id: String?,
    val label: String,
)

data class CustomField(
    val id: Long,
    val name: String,
    val dataType: CustomFieldDataType,
    /** Options of a select field, empty for other types. */
    val selectOptions: List<SelectOption>,
    /** ISO 4217 code used for monetary values without a currency, if set. */
    val defaultCurrency: String?,
    val documentCount: Int?,
)

/**
 * The desired state of a custom field (see [TagDraft] for how create and update use it). [dataType] is only
 * used on create; the server does not allow changing it.
 *
 * @property selectOptions select fields only, at least one. Existing options keep their [SelectOption.id]
 *   so that documents referencing them stay valid; new options have `id = null` and get one from the server.
 * @property defaultCurrency monetary fields only: an ISO 4217 code (the server requires exactly 3 characters),
 *   or `null` for none.
 */
data class CustomFieldDraft(
    val name: String,
    val dataType: CustomFieldDataType,
    val selectOptions: List<SelectOption> = emptyList(),
    val defaultCurrency: String? = null,
) {
    init {
        if (dataType == CustomFieldDataType.SELECT) {
            require(selectOptions.isNotEmpty()) { "Select fields need at least one option" }
        } else {
            require(selectOptions.isEmpty()) { "Only select fields have options" }
        }
        require(defaultCurrency == null || dataType == CustomFieldDataType.MONETARY) {
            "Only monetary fields have a default currency"
        }
        require(defaultCurrency == null || defaultCurrency.length == 3) {
            "Default currency must have 3 characters: $defaultCurrency"
        }
    }
}

fun CustomField.toDraft(): CustomFieldDraft = CustomFieldDraft(name, dataType, selectOptions, defaultCurrency)
