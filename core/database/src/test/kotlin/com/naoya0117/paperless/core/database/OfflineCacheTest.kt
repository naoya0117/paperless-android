package com.naoya0117.paperless.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class OfflineCacheTest {
    private val cache = OfflineCache.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())

    @After
    fun tearDown() = cache.close()

    private val now = Instant.parse("2026-11-10T00:00:00Z")

    private val matching = Matching(MatchingAlgorithm.REGEX, "^inv", isInsensitive = false)

    private fun tag(
        id: Long,
        name: String,
    ) = Tag(id, name, name.lowercase(), matching, 4, 1, true, "#a6cee3", "#000000", false, null, listOf(7, 8))

    @Test
    fun `every kind round trips`() =
        runTest {
            val tag = tag(1, "Invoice").copy(isInboxTag = true, parentId = 9)
            val correspondent =
                Correspondent(2, "ACME", "acme", matching, null, null, false, LocalDate.of(2026, 11, 9))
            val documentType = DocumentType(3, "Bill", "bill", matching, 0, 1, true)
            val storagePath =
                StoragePath(4, "Archive", "archive", matching, 2, 1, true, "{{ created_year }}/{{ title }}")
            val customField =
                CustomField(
                    5,
                    "Status",
                    CustomFieldDataType.SELECT,
                    listOf(SelectOption("a1", "Open"), SelectOption(null, "Legacy")),
                    null,
                    3,
                )
            val savedView =
                SavedView(
                    6,
                    "Inbox",
                    "inbox",
                    "created",
                    true,
                    listOf(FilterRule(6, "1"), FilterRule(3, null)),
                    50,
                    "table",
                    null,
                    1,
                    true,
                )

            cache.tags.replaceAll("a", listOf(tag), now)
            cache.correspondents.replaceAll("a", listOf(correspondent), now)
            cache.documentTypes.replaceAll("a", listOf(documentType), now)
            cache.storagePaths.replaceAll("a", listOf(storagePath), now)
            cache.customFields.replaceAll("a", listOf(customField), now)
            cache.savedViews.replaceAll(
                "a",
                listOf(savedView, savedView.copy(id = 7, displayFields = listOf("title"))),
                now,
            )

            assertEquals(listOf(tag), cache.tags.observe("a").first())
            assertEquals(listOf(correspondent), cache.correspondents.observe("a").first())
            assertEquals(listOf(documentType), cache.documentTypes.observe("a").first())
            assertEquals(listOf(storagePath), cache.storagePaths.observe("a").first())
            assertEquals(listOf(customField), cache.customFields.observe("a").first())
            assertEquals(
                listOf(savedView, savedView.copy(id = 7, displayFields = listOf("title"))),
                cache.savedViews.observe("a").first(),
            )
        }

    @Test
    fun `lists are per account and ordered by name`() =
        runTest {
            cache.tags.replaceAll("a", listOf(tag(1, "b"), tag(2, "A"), tag(3, "c")), now)
            cache.tags.replaceAll("b", listOf(tag(1, "other")), now)

            assertEquals(
                listOf("A", "b", "c"),
                cache.tags
                    .observe("a")
                    .first()
                    .map { it.name },
            )
            assertEquals(
                listOf("other"),
                cache.tags
                    .observe("b")
                    .first()
                    .map { it.name },
            )
        }

    @Test
    fun `replacing drops items the server no longer has`() =
        runTest {
            cache.tags.replaceAll("a", listOf(tag(1, "one"), tag(2, "two")), now)

            cache.tags.replaceAll("a", listOf(tag(2, "two (renamed)")), now)

            assertEquals(listOf(tag(2, "two (renamed)")), cache.tags.observe("a").first())
        }

    @Test
    fun `upsert and delete change single items`() =
        runTest {
            cache.tags.replaceAll("a", listOf(tag(1, "one")), now)
            cache.tags.replaceAll("b", listOf(tag(1, "one")), now)

            cache.tags.upsert("a", listOf(tag(2, "two"), tag(3, "three")))
            cache.tags.upsert("a", listOf(tag(1, "uno")))
            cache.tags.delete("a", listOf(2, 3))

            assertEquals(listOf(tag(1, "uno")), cache.tags.observe("a").first())
            assertEquals(listOf(tag(1, "one")), cache.tags.observe("b").first())
        }

    @Test
    fun `clearing removes only that account`() =
        runTest {
            cache.tags.replaceAll("a", listOf(tag(1, "one")), now)
            cache.savedViews.replaceAll(
                "a",
                listOf(SavedView(1, "v", null, null, false, emptyList(), null, null, null, null, true)),
                now,
            )
            cache.tags.replaceAll("b", listOf(tag(1, "one")), now)

            cache.clear("a")

            assertEquals(emptyList<Tag>(), cache.tags.observe("a").first())
            assertEquals(emptyList<SavedView>(), cache.savedViews.observe("a").first())
            assertEquals(
                1,
                cache.tags
                    .observe("b")
                    .first()
                    .size,
            )
        }

    @Test
    fun `the refresh time is kept per account and kind until cleared`() =
        runTest {
            assertEquals(null, cache.tags.observeRefreshedAt("a").first())

            cache.tags.replaceAll("a", emptyList(), now)
            cache.tags.upsert("a", listOf(tag(1, "one")))

            assertEquals(now, cache.tags.observeRefreshedAt("a").first())
            assertEquals(null, cache.correspondents.observeRefreshedAt("a").first())
            assertEquals(null, cache.tags.observeRefreshedAt("b").first())
            cache.clear("a")
            assertEquals(null, cache.tags.observeRefreshedAt("a").first())
        }

    @Test
    fun `names are sorted for people, not by code point`() =
        runTest {
            cache.tags.replaceAll("a", listOf(tag(1, "Zebra"), tag(2, "Été"), tag(3, "apple")), now)

            assertEquals(
                listOf("apple", "Été", "Zebra"),
                cache.tags
                    .observe("a")
                    .first()
                    .map { it.name },
            )
        }
}
