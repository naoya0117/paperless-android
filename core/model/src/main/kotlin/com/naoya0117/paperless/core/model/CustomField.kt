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

/** An option of a [CustomFieldDataType.SELECT] field; documents store the [id]. */
data class SelectOption(
    val id: String,
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
 * Values to create or update a custom field with. The data type cannot be changed after creation.
 *
 * @property selectOptions required for select fields, on every update too (the server rejects updates of
 *   select fields without them). Existing options keep their [SelectOptionDraft.id] so that documents
 *   referencing them stay valid; new options have `id = null` and get one from the server.
 */
data class CustomFieldDraft(
    val name: String,
    val dataType: CustomFieldDataType,
    val selectOptions: List<SelectOptionDraft>? = null,
    val defaultCurrency: String? = null,
) {
    init {
        require(dataType != CustomFieldDataType.SELECT || !selectOptions.isNullOrEmpty()) {
            "Select fields need at least one option"
        }
    }
}

data class SelectOptionDraft(
    val id: String?,
    val label: String,
)
