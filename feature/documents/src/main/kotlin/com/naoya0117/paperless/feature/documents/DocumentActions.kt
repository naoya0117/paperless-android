package com.naoya0117.paperless.feature.documents

import android.content.Context
import com.naoya0117.paperless.core.data.CachedDocumentFile
import com.naoya0117.paperless.core.data.DocumentFileRepository
import com.naoya0117.paperless.core.data.DocumentRepository
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.network.DocumentFileKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * What can be done with a document's file or the document itself, from the viewer or a list: hand the file
 * to another app, save it to Downloads, move the document to the trash. Each returns the event to show;
 * failures are thrown ([LocalFailure] for the device's side), see [failed].
 */
class DocumentActions
    @Inject
    constructor(
        private val files: DocumentFileRepository,
        private val documents: DocumentRepository,
        @ApplicationContext private val context: Context,
    ) {
        /** The file (the archived PDF if there is one) for another app: to share ([open] `false`) or open. */
        suspend fun send(
            document: Document,
            open: Boolean,
        ): ViewerEvent.SendFile {
            val file = files.file(document, DocumentFileKind.DOWNLOAD)
            val type = mimeType(document, file)
            val uri = local { DocumentFileExport.shareable(context, file, fileName(document, file, type)) }
            return ViewerEvent.SendFile(uri, type ?: ANY_TYPE, open = open)
        }

        /** Saves the file to the device's Downloads. */
        suspend fun download(document: Document): ViewerEvent.Saved {
            val file = files.file(document, DocumentFileKind.DOWNLOAD)
            val type = mimeType(document, file)
            val saved =
                local { DocumentFileExport.saveToDownloads(context, file, fileName(document, file, type), type) }
            return ViewerEvent.Saved(saved)
        }

        /** Moves the document to the trash (and out of the cached lists). */
        suspend fun delete(document: Document): ViewerEvent.Deleted {
            documents.delete(document.id)
            return ViewerEvent.Deleted
        }

        /** A failure on the device (storing or copying the file), not of the server or the connection. */
        class LocalFailure(
            cause: Throwable,
        ) : Exception(cause)

        private inline fun <T> local(block: () -> T): T =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                throw LocalFailure(e)
            }

        private fun fileName(
            document: Document,
            file: CachedDocumentFile,
            mimeType: String?,
        ): String =
            DocumentFileExport.fileName(
                file.fileName ?: document.archivedFileName ?: document.originalFileName,
                document.title,
                mimeType,
            )

        companion object {
            private const val ANY_TYPE = "*/*"

            /**
             * The event for [error] of [action]: the device's side ([LocalFailure]), or the server's (downloading or
             * deleting failed: PaperlessException, NotSignedInException when signed out meanwhile, ...).
             */
            fun failed(
                action: FileAction,
                error: Exception,
            ): ViewerEvent.Failed =
                ViewerEvent.Failed(action, if (error is LocalFailure) FailureReason.STORAGE else FailureReason.SERVER)

            /** Without parameters such as "; charset=binary". */
            fun mimeType(
                document: Document,
                file: CachedDocumentFile,
            ): String? = (file.contentType ?: document.mimeType)?.substringBefore(';')?.trim()?.lowercase()
        }
    }
