package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentDraft
import com.naoya0117.paperless.core.model.DocumentMetadata
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSuggestions
import com.naoya0117.paperless.core.model.HistoryEntry
import com.naoya0117.paperless.core.model.Note
import com.naoya0117.paperless.core.model.Page
import com.naoya0117.paperless.core.network.DocumentFile
import com.naoya0117.paperless.core.network.DocumentFileKind
import com.naoya0117.paperless.core.network.DocumentsApi
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.internal.dto.NoteDto
import com.naoya0117.paperless.core.network.internal.dto.NoteRequestDto
import com.naoya0117.paperless.core.network.internal.dto.toModel
import com.naoya0117.paperless.core.network.internal.dto.toPatchBody
import com.naoya0117.paperless.core.network.internal.dto.toQueryMap
import com.naoya0117.paperless.core.network.internal.http.fileNameFromContentDisposition
import com.naoya0117.paperless.core.network.internal.service.DocumentService

internal class DocumentsApiImpl(
    private val service: DocumentService,
) : DocumentsApi {
    override suspend fun list(query: DocumentQuery): Page<Document> =
        pageOrEmptyBeyondEnd(query.page) { service.list(query.toQueryMap()) }.toModel { parsing { it.toModel() } }

    override suspend fun get(id: Long): Document = parsing { service.get(id).toModel() }

    override suspend fun update(
        current: Document,
        draft: DocumentDraft,
    ): Document {
        val body = draft.toPatchBody(current)
        if (body.isEmpty()) return current
        // The response carries `permissions` instead of `user_can_change`; a successful PATCH proves the right.
        return parsing { service.update(current.id, body).toModel() }.copy(userCanChange = true)
    }

    override suspend fun delete(id: Long) = service.delete(id)

    override suspend fun openFile(
        id: Long,
        kind: DocumentFileKind,
    ): DocumentFile {
        val (path, original) =
            when (kind) {
                DocumentFileKind.THUMBNAIL -> "thumb" to null
                DocumentFileKind.PREVIEW -> "preview" to null
                DocumentFileKind.DOWNLOAD -> "download" to null
                DocumentFileKind.ORIGINAL -> "download" to true
            }
        val response = service.file(id, path, original)
        val body = response.body() ?: throw PaperlessException.InvalidResponse("empty body")
        return DocumentFile(
            contentType = body.contentType()?.toString(),
            fileName = fileNameFromContentDisposition(response.headers()["Content-Disposition"]),
            contentLength = body.contentLength().takeIf { it >= 0 },
            stream = body.byteStream(),
        )
    }

    override suspend fun addNote(
        documentId: Long,
        text: String,
    ): List<Note> {
        require(text.isNotBlank()) { "A note must not be blank" }
        return parsing { service.addNote(documentId, NoteRequestDto(text)).toNotes() }
    }

    override suspend fun deleteNote(
        documentId: Long,
        noteId: Long,
    ): List<Note> = parsing { service.deleteNote(documentId, noteId).toNotes() }

    override suspend fun metadata(id: Long): DocumentMetadata = service.metadata(id).toModel()

    override suspend fun suggestions(id: Long): DocumentSuggestions = service.suggestions(id).toModel()

    override suspend fun history(id: Long): List<HistoryEntry> = parsing { service.history(id).map { it.toModel() } }

    override suspend fun nextArchiveSerialNumber(): Long = service.nextAsn()

    private fun List<NoteDto>.toNotes(): List<Note> = map { it.toModel() }.sortedBy { it.created }
}
