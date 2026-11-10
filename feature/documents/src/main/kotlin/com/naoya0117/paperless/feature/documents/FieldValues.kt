package com.naoya0117.paperless.feature.documents

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.naoya0117.paperless.core.model.CustomField
import com.naoya0117.paperless.core.model.CustomFieldDataType
import com.naoya0117.paperless.core.model.FieldValue
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Currency

/** A custom field value as text to show; `null` if it is empty. [field] is `null` if not cached. */
@Composable
internal fun formatFieldValue(
    field: CustomField?,
    value: FieldValue,
): String? =
    when (value) {
        FieldValue.Empty -> null
        is FieldValue.Bool -> stringResource(if (value.value) R.string.info_yes else R.string.info_no)
        is FieldValue.Integer -> NumberFormat.getIntegerInstance().format(value.value)
        // All digits as stored (by default at most 3 fraction digits are shown).
        is FieldValue.Decimal ->
            NumberFormat.getNumberInstance().apply { maximumFractionDigits = MAX_FRACTION_DIGITS }.format(value.value)
        is FieldValue.DocumentLinks ->
            stringResource(R.string.info_linked_documents, value.documentIds.joinToString { "#$it" })
        is FieldValue.Unknown -> value.json
        is FieldValue.Text -> formatText(field, value.value)
    }

private fun formatText(
    field: CustomField?,
    text: String,
): String? {
    if (text.isEmpty()) return null
    return when (field?.dataType) {
        CustomFieldDataType.SELECT -> field.selectOptions.firstOrNull { it.id == text }?.label ?: text
        CustomFieldDataType.DATE ->
            try {
                LocalDate.parse(text).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            } catch (_: DateTimeParseException) {
                text
            }
        CustomFieldDataType.MONETARY -> formatMoney(text, field.defaultCurrency)
        else -> text
    }
}

/** `EUR120.00` or `120.00` (in [defaultCurrency], if any) → e.g. `€120.00`. */
internal fun formatMoney(
    text: String,
    defaultCurrency: String?,
): String {
    val code = text.takeWhile { it.isLetter() }.ifEmpty { defaultCurrency }
    val amount = text.dropWhile { it.isLetter() }.toBigDecimalOrNull() ?: return text
    val currency = code?.let { runCatching { Currency.getInstance(it.uppercase()) }.getOrNull() }
    return if (currency == null) {
        listOfNotNull(code, plain(amount)).joinToString(" ")
    } else {
        NumberFormat.getCurrencyInstance().apply { this.currency = currency }.format(amount)
    }
}

private fun plain(amount: BigDecimal): String =
    NumberFormat
        .getNumberInstance()
        .apply {
            minimumFractionDigits = 2
        }.format(amount)

private const val MAX_FRACTION_DIGITS = 15
