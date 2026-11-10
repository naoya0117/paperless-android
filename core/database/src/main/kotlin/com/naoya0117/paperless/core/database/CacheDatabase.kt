package com.naoya0117.paperless.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TagEntity::class,
        CorrespondentEntity::class,
        DocumentTypeEntity::class,
        StoragePathEntity::class,
        CustomFieldEntity::class,
        SavedViewEntity::class,
        CacheMetaEntity::class,
        DocumentEntity::class,
        DocumentListEntity::class,
        DocumentListItemEntity::class,
    ],
    version = 2,
)
internal abstract class CacheDatabase : RoomDatabase() {
    abstract fun tags(): TagDao

    abstract fun correspondents(): CorrespondentDao

    abstract fun documentTypes(): DocumentTypeDao

    abstract fun storagePaths(): StoragePathDao

    abstract fun customFields(): CustomFieldDao

    abstract fun savedViews(): SavedViewDao

    abstract fun cacheMeta(): CacheMetaDao

    abstract fun documents(): DocumentDao
}
