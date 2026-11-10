package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.model.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The trash of the active account: deleted documents, restorable until the server empties it. Not cached:
 * it is only shown online.
 */
@Singleton
class TrashRepository
    @Inject
    constructor(
        private val accounts: AccountRepository,
    ) {
        /**
         * The documents in the trash, most recently deleted first (at most [MAX_DOCUMENTS]).
         *
         * @throws NotSignedInException
         * @throws com.naoya0117.paperless.core.network.PaperlessException
         */
        suspend fun documents(): List<Document> {
            val trash = session().client.trash
            val documents = mutableListOf<Document>()
            var page = 1
            while (documents.size < MAX_DOCUMENTS) {
                val result = trash.list(page = page, pageSize = PAGE_SIZE)
                documents += result.items
                if (!result.hasNext || result.items.isEmpty()) break
                page++
            }
            // Pages may shift while loading (no order on the server): each document once.
            return documents.distinctBy { it.id }.sortedByDescending { it.deletedAt }
        }

        /** @return the IDs the server restored */
        suspend fun restore(ids: Collection<Long>): List<Long> = session().client.trash.restore(ids)

        /** Deletes the documents permanently. @return the IDs the server deleted */
        suspend fun delete(ids: Collection<Long>): List<Long> = session().client.trash.empty(ids)

        /** Deletes every document in the trash permanently. @return the IDs the server deleted */
        suspend fun deleteAll(): List<Long> = session().client.trash.emptyAll()

        private suspend fun session() = accounts.currentSession() ?: throw NotSignedInException()

        companion object {
            const val MAX_DOCUMENTS = 1000
            private const val PAGE_SIZE = 100
        }
    }
