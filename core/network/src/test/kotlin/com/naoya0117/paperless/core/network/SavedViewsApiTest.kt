package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSearch
import com.naoya0117.paperless.core.model.FilterRule
import com.naoya0117.paperless.core.model.SavedViewDraft
import com.naoya0117.paperless.core.model.SavedViewVisibility
import com.naoya0117.paperless.core.model.toDraft
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SavedViewsApiTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client() = server.client { PaperlessClientConfig(it, AuthToken("abc")) }

    @Test
    fun `reads saved views`() =
        runTest {
            server.enqueue(jsonResponse(200, "saved_views.json"))

            val views =
                client()
                    .savedViews
                    .list()
                    .items
                    .associateBy { it.name }

            val invoices = views.getValue("Invoices")
            assertEquals(listOf(FilterRule(6, "1")), invoices.filterRules)
            assertEquals("created", invoices.sortField)
            assertEquals(true, invoices.sortReverse)
            assertEquals("table", invoices.displayMode)
            assertEquals(listOf("title", "created", "tag"), invoices.displayFields)
            assertEquals(listOf(FilterRule(3, null)), views.getValue("No correspondent").filterRules)
        }

    @Test
    fun `a saved view becomes a document query`() =
        runTest {
            server.enqueue(jsonResponse(200, "saved_views.json"))
            server.enqueue(jsonResponse(200, "documents.json"))
            val client = client()
            val invoices =
                client.savedViews
                    .list()
                    .items
                    .single { it.name == "Invoices" }
            server.takeRequest()

            client.documents.list(invoices.toDocumentQuery(page = 2))

            val url = server.takeRequest().url
            assertEquals("1", url.queryParameter("tags__id__all"))
            assertEquals("-created", url.queryParameter("ordering"))
            assertEquals("2", url.queryParameter("page"))
            assertEquals("25", url.queryParameter("page_size"))
        }

    @Test
    fun `typed query parameters win over extra ones and page cannot be overridden`() =
        runTest {
            server.enqueue(jsonResponse(200, "documents.json"))

            client().documents.list(
                DocumentQuery(
                    page = 3,
                    search = DocumentSearch.Text("typed"),
                    extraParams = mapOf("text" to "extra", "page" to "9", "mime_type" to "application/pdf"),
                ),
            )

            val url = server.takeRequest().url
            assertEquals("typed", url.queryParameter("text"))
            assertEquals("3", url.queryParameter("page"))
            assertEquals("application/pdf", url.queryParameter("mime_type"))
        }

    @Test
    fun `creates and updates saved views`() =
        runTest {
            server.enqueue(jsonResponse(200, "saved_views.json"))
            server.enqueue(
                okJson("""{"id": 2, "name": "Renamed", "filter_rules": [{"rule_type": 3, "value": null}]}"""),
            )
            server.enqueue(okJson("""{"id": 5, "name": "New"}"""))
            val client = client()
            val view =
                client.savedViews
                    .list()
                    .items
                    .single { it.name == "No correspondent" }

            client.savedViews.update(view, view.toDraft().copy(name = "Renamed"))
            client.savedViews.create(SavedViewDraft("New", listOf(FilterRule(17, "3"), FilterRule(3, null))))

            server.takeRequest()
            assertEquals("""{"name":"Renamed"}""", server.takeRequest().body?.utf8())
            assertEquals(
                """{"name":"New","filter_rules":[{"rule_type":17,"value":"3"},{"rule_type":3,"value":null}],""" +
                    """"sort_field":"created","sort_reverse":true,"page_size":null,"display_mode":null,"display_fields":[]}""",
                server.takeRequest().body?.utf8(),
            )
        }

    @Test
    fun `reads which views are shown`() =
        runTest {
            server.enqueue(uiSettingsResponse())
            server.enqueue(jsonResponse(200, "ui_settings_saved_views.json", "X-Api-Version" to "10"))
            val client = client()

            assertEquals(SavedViewVisibility(emptyList(), emptyList()), client.server.fetchSavedViewVisibility())
            val visibility = client.server.fetchSavedViewVisibility()
            assertEquals(1, visibility.dashboardViewIds.size)
            assertEquals(2, visibility.sidebarViewIds.size)
        }

    private fun okJson(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .body(body)
            .build()
}
