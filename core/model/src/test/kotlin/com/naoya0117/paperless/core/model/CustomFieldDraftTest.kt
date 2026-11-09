package com.naoya0117.paperless.core.model

import org.junit.Assert.assertThrows
import org.junit.Test

class CustomFieldDraftTest {
    @Test
    fun `select fields need options and only they have options`() {
        assertThrows(IllegalArgumentException::class.java) { CustomFieldDraft("P", CustomFieldDataType.SELECT) }
        assertThrows(IllegalArgumentException::class.java) {
            CustomFieldDraft("M", CustomFieldDataType.MONETARY, selectOptions = listOf(SelectOption(null, "x")))
        }
        CustomFieldDraft("P", CustomFieldDataType.SELECT, listOf(SelectOption(null, "High")))
    }

    @Test
    fun `default currency is a 3-letter code on monetary fields`() {
        CustomFieldDraft("M", CustomFieldDataType.MONETARY, defaultCurrency = "EUR")
        CustomFieldDraft("M", CustomFieldDataType.MONETARY, defaultCurrency = null)
        assertThrows(IllegalArgumentException::class.java) {
            CustomFieldDraft("M", CustomFieldDataType.MONETARY, defaultCurrency = "usdd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CustomFieldDraft("D", CustomFieldDataType.DATE, defaultCurrency = "EUR")
        }
    }
}
