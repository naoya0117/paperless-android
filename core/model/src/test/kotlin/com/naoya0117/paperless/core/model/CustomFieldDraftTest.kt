package com.naoya0117.paperless.core.model

import org.junit.Assert.assertThrows
import org.junit.Test

class CustomFieldDraftTest {
    @Test
    fun `select fields need options`() {
        assertThrows(IllegalArgumentException::class.java) { CustomFieldDraft("P", CustomFieldDataType.SELECT) }
        assertThrows(IllegalArgumentException::class.java) {
            CustomFieldDraft("P", CustomFieldDataType.SELECT, selectOptions = emptyList())
        }
        CustomFieldDraft("P", CustomFieldDataType.SELECT, listOf(SelectOptionDraft(null, "High")))
        CustomFieldDraft("D", CustomFieldDataType.DATE)
    }
}
