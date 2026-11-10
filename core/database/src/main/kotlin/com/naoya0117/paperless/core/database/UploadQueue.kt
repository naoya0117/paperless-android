package com.naoya0117.paperless.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.sqlite.SQLiteDriver
import com.naoya0117.paperless.core.model.DocumentUpload
import com.naoya0117.paperless.core.model.Upload
import com.naoya0117.paperless.core.model.UploadError
import com.naoya0117.paperless.core.model.UploadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/**
 * Files waiting to be uploaded, with their progress and outcome. Unlike [OfflineCache] this is the user's
 * data, so it is kept in its own database that is migrated, never rebuilt.
 */
class UploadQueue private constructor(
    private val database: UploadDatabase,
) {
    private val dao = database.uploads()

    /** The account's uploads, newest first. */
    fun observe(accountId: String): Flow<List<Upload>> =
        dao
            .observe(accountId)
            .map { rows -> rows.map { it.toModel() } }
            .distinctUntilChanged()

    suspend fun get(id: String): Upload? = dao.get(id)?.toModel()

    /** The local copy of the file to send. */
    suspend fun filePath(id: String): String? = dao.get(id)?.filePath

    suspend fun add(
        upload: Upload,
        filePath: String,
    ) = dao.insert(upload.toEntity(filePath))

    /** Replaces the stored state of an existing upload (matched by [Upload.id]); `false` if it is gone. */
    suspend fun update(upload: Upload): Boolean = dao.update(upload.toStatus()) > 0

    /** Uploads not finished yet (e.g. to make sure they are scheduled). */
    suspend fun unfinished(): List<Upload> = dao.unfinished().map { it.toModel() }

    /** Only updates the progress, so frequent calls stay cheap. */
    suspend fun setSentBytes(
        id: String,
        sentBytes: Long,
    ) = dao.setSentBytes(id, sentBytes)

    suspend fun remove(id: String) = dao.delete(id)

    /** All uploads of the account (e.g. to cancel and delete them after signing out). */
    suspend fun forAccount(accountId: String): List<Upload> = dao.forAccount(accountId).map { it.toModel() }

    /** File paths of every upload, e.g. to find local copies nothing refers to anymore. */
    suspend fun allFilePaths(): Set<String> = dao.allFilePaths().toSet()

    /** For tests. */
    internal fun close() = database.close()

    companion object {
        fun create(context: Context): UploadQueue =
            UploadQueue(Room.databaseBuilder(context, UploadDatabase::class.java, "uploads.db").build())

        /** An in-memory queue for tests; see [OfflineCache.inMemory] for [driver]. */
        fun inMemory(
            context: Context,
            driver: SQLiteDriver? = null,
        ): UploadQueue =
            UploadQueue(
                Room
                    .inMemoryDatabaseBuilder(context, UploadDatabase::class.java)
                    .apply { driver?.let { setDriver(it) } }
                    .build(),
            )
    }
}

@Entity(tableName = "uploads")
internal data class UploadEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val fileName: String,
    val mimeType: String?,
    val size: Long,
    /** The app-private copy of the file. */
    val filePath: String,
    val title: String?,
    /** ISO date. */
    val created: String?,
    val correspondentId: Long?,
    val documentTypeId: Long?,
    val storagePathId: Long?,
    val tagIdsJson: String,
    val archiveSerialNumber: Long?,
    val customFieldsJson: String,
    val state: String,
    val sentBytes: Long,
    val taskId: String?,
    val documentId: Long?,
    val duplicateOfDocumentId: Long?,
    val errorKind: String?,
    val error: String?,
    /** Epoch milliseconds. */
    val createdAt: Long,
)

/** The columns of [UploadEntity] that change while an upload runs. */
internal data class UploadStatus(
    val id: String,
    val title: String?,
    val created: String?,
    val correspondentId: Long?,
    val documentTypeId: Long?,
    val storagePathId: Long?,
    val tagIdsJson: String,
    val archiveSerialNumber: Long?,
    val customFieldsJson: String,
    val state: String,
    val sentBytes: Long,
    val taskId: String?,
    val documentId: Long?,
    val duplicateOfDocumentId: Long?,
    val errorKind: String?,
    val error: String?,
)

@Dao
internal abstract class UploadDao {
    @Query("SELECT * FROM uploads WHERE accountId = :accountId ORDER BY createdAt DESC, id")
    abstract fun observe(accountId: String): Flow<List<UploadEntity>>

    @Query("SELECT * FROM uploads WHERE id = :id")
    abstract suspend fun get(id: String): UploadEntity?

    @Query("SELECT * FROM uploads WHERE accountId = :accountId")
    abstract suspend fun forAccount(accountId: String): List<UploadEntity>

    @Query("SELECT filePath FROM uploads")
    abstract suspend fun allFilePaths(): List<String>

    @Insert
    abstract suspend fun insert(upload: UploadEntity)

    /** Everything but the file. */
    @Update(entity = UploadEntity::class)
    abstract suspend fun update(status: UploadStatus): Int

    @Query("SELECT * FROM uploads WHERE state NOT IN ('DONE', 'FAILED')")
    abstract suspend fun unfinished(): List<UploadEntity>

    @Query("UPDATE uploads SET sentBytes = :sentBytes WHERE id = :id")
    abstract suspend fun setSentBytes(
        id: String,
        sentBytes: Long,
    )

    @Query("DELETE FROM uploads WHERE id = :id")
    abstract suspend fun delete(id: String)
}

@Database(entities = [UploadEntity::class], version = 1)
internal abstract class UploadDatabase : RoomDatabase() {
    abstract fun uploads(): UploadDao
}

private fun Upload.toEntity(filePath: String) =
    UploadEntity(
        id = id,
        accountId = accountId,
        fileName = fileName,
        mimeType = mimeType,
        size = size,
        filePath = filePath,
        title = metadata.title,
        created = metadata.created?.toString(),
        correspondentId = metadata.correspondentId,
        documentTypeId = metadata.documentTypeId,
        storagePathId = metadata.storagePathId,
        tagIdsJson = encodeIds(metadata.tagIds),
        archiveSerialNumber = metadata.archiveSerialNumber,
        customFieldsJson = encodeCustomFields(metadata.customFields),
        state = state.name,
        sentBytes = sentBytes,
        taskId = taskId,
        documentId = documentId,
        duplicateOfDocumentId = duplicateOfDocumentId,
        errorKind = errorKind?.name,
        error = error,
        createdAt = createdAt.toEpochMilli(),
    )

private fun Upload.toStatus() =
    toEntity("").let {
        UploadStatus(
            it.id,
            it.title,
            it.created,
            it.correspondentId,
            it.documentTypeId,
            it.storagePathId,
            it.tagIdsJson,
            it.archiveSerialNumber,
            it.customFieldsJson,
            it.state,
            it.sentBytes,
            it.taskId,
            it.documentId,
            it.duplicateOfDocumentId,
            it.errorKind,
            it.error,
        )
    }

private fun UploadEntity.toModel() =
    Upload(
        id = id,
        accountId = accountId,
        fileName = fileName,
        mimeType = mimeType,
        size = size,
        metadata =
            DocumentUpload(
                title = title,
                created = created?.let(LocalDate::parse),
                correspondentId = correspondentId,
                documentTypeId = documentTypeId,
                storagePathId = storagePathId,
                tagIds = decodeIds(tagIdsJson),
                archiveSerialNumber = archiveSerialNumber,
                customFields = decodeCustomFields(customFieldsJson),
            ),
        // A state written by a newer app version reads as failed rather than crashing.
        state = UploadState.entries.firstOrNull { it.name == state } ?: UploadState.FAILED,
        sentBytes = sentBytes,
        taskId = taskId,
        documentId = documentId,
        duplicateOfDocumentId = duplicateOfDocumentId,
        errorKind =
            errorKind?.let { kind ->
                UploadError.entries.firstOrNull { it.name == kind }
                    ?: UploadError.UNEXPECTED
            },
        error = error,
        createdAt = Instant.ofEpochMilli(createdAt),
    )
