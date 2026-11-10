package com.naoya0117.paperless.core.database

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.model.Correspondent
import com.naoya0117.paperless.core.model.CustomField
import com.naoya0117.paperless.core.model.CustomFieldDataType
import com.naoya0117.paperless.core.model.CustomFieldValue
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentType
import com.naoya0117.paperless.core.model.FieldValue
import com.naoya0117.paperless.core.model.FilterRule
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingAlgorithm
import com.naoya0117.paperless.core.model.Note
import com.naoya0117.paperless.core.model.NoteAuthor
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.core.model.SearchHit
import com.naoya0117.paperless.core.model.SelectOption
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.Tag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun document(
        id: Long,
        title: String = "Doc $id",
    ) = Document(
        id = id,
        title = title,
        content = "short",
        created = LocalDate.of(2026, 1, 2),
        added = Instant.parse("2026-01-02T03:04:05Z"),
        modified = Instant.parse("2026-01-03T03:04:05Z"),
        correspondentId = 2,
        documentTypeId = null,
        storagePathId = 4,
        tagIds = listOf(1, 3),
        archiveSerialNumber = 17,
        originalFileName = "scan.pdf",
        archivedFileName = null,
        mimeType = "application/pdf",
        pageCount = 2,
        customFields =
            listOf(
                CustomFieldValue(1, FieldValue.Text("EUR12.00")),
                CustomFieldValue(2, FieldValue.Integer(5)),
                CustomFieldValue(3, FieldValue.Decimal(1.5)),
                CustomFieldValue(4, FieldValue.Bool(true)),
                CustomFieldValue(5, FieldValue.DocumentLinks(listOf(8, 9))),
                CustomFieldValue(6, FieldValue.Unknown("""{"x":1}""")),
                CustomFieldValue(7, FieldValue.Empty),
            ),
        notes = listOf(Note(1, "note", Instant.parse("2026-01-04T00:00:00Z"), NoteAuthor(3, "alice", "Alice", null))),
        owner = 3,
        userCanChange = true,
        deletedAt = null,
        searchHit = SearchHit(1.5, 1, "<b>Doc</b>"),
    )

    private suspend fun page(
        accountId: String,
        listKey: String,
    ): List<Document> {
        val result = TestPager(PagingConfig(pageSize = 50), cache.documents.list(accountId, listKey)).refresh()
        return (result as PagingSource.LoadResult.Page).data
    }

    @Test
    fun `documents round trip with their search hit per list`() =
        runTest {
            cache.documents.replaceList("a", "search", listOf(document(1)), nextPage = 2, refreshedAt = now)
            cache.documents.replaceList(
                "a",
                "all",
                listOf(document(1).copy(searchHit = null)),
                nextPage = null,
                refreshedAt = now,
            )

            assertEquals(listOf(document(1)), page("a", "search"))
            assertEquals(listOf(document(1).copy(searchHit = null)), page("a", "all"))
            assertEquals(DocumentListState(2, now), cache.documents.listState("a", "search"))
        }

    @Test
    fun `appending skips documents already in the list`() =
        runTest {
            cache.documents.replaceList("a", "all", listOf(document(1), document(2)), nextPage = 2, refreshedAt = now)

            assertTrue(cache.documents.appendToList("a", "all", listOf(document(2), document(3)), nextPage = null))

            assertEquals(listOf(1L, 2L, 3L), page("a", "all").map { it.id })
            assertEquals(null, cache.documents.listState("a", "all")?.nextPage)
            assertEquals(false, cache.documents.appendToList("a", "unknown", listOf(document(4)), nextPage = null))
        }

    @Test
    fun `old lists and documents no list contains are dropped`() =
        runTest {
            // The list being written is kept besides MAX_LISTS others.
            for (i in 0..CachedDocuments.MAX_LISTS + 1) {
                cache.documents.replaceList(
                    "a",
                    "list$i",
                    listOf(document(i.toLong())),
                    null,
                    now.plusSeconds(i.toLong()),
                )
            }

            assertEquals(null, cache.documents.listState("a", "list0"))
            assertEquals(null, cache.documents.observe("a", 0).first())
            assertEquals(emptyList<Document>(), page("a", "list0"))
            assertEquals(listOf(1L), page("a", "list1").map { it.id })
        }

    @Test
    fun `document updates and deletions apply to every list`() =
        runTest {
            cache.documents.replaceList("a", "one", listOf(document(1), document(2)), null, now)
            cache.documents.replaceList("a", "two", listOf(document(1)), null, now)

            cache.documents.update("a", document(1, "Renamed").copy(searchHit = null))
            cache.documents.update("a", document(99))
            cache.documents.delete("a", 2)

            assertEquals(listOf("Renamed"), page("a", "two").map { it.title })
            assertEquals(listOf(1L), page("a", "one").map { it.id })
            assertEquals("not cached documents are not added", null, cache.documents.observe("a", 99).first())
            cache.clear("a")
            assertEquals(null, cache.documents.observe("a", 1).first())
            assertEquals(null, cache.documents.listState("a", "one"))
        }
}
