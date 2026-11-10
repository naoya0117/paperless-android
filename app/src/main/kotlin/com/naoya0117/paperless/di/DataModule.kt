package com.naoya0117.paperless.di

import android.content.Context
import androidx.work.WorkManager
import com.naoya0117.paperless.core.data.AccountDataCleaner
import com.naoya0117.paperless.core.data.ClientCertificateSource
import com.naoya0117.paperless.core.data.DocumentFileRepository
import com.naoya0117.paperless.core.data.DocumentFileStorage
import com.naoya0117.paperless.core.data.KeyChainClientCertificateSource
import com.naoya0117.paperless.core.data.UploadProcessor
import com.naoya0117.paperless.core.data.UploadRepository
import com.naoya0117.paperless.core.data.UploadScheduler
import com.naoya0117.paperless.core.data.UploadStorage
import com.naoya0117.paperless.core.data.UploadWorkerFactory
import com.naoya0117.paperless.core.data.WorkManagerUploadScheduler
import com.naoya0117.paperless.core.database.OfflineCache
import com.naoya0117.paperless.core.database.UploadQueue
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.AndroidKeystoreCipher
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun accountStore(
        @ApplicationContext context: Context,
    ): AccountStore = AccountStore.create(File(context.filesDir, "datastore/accounts.json"), AndroidKeystoreCipher())

    @Provides
    @Singleton
    fun clientCertificateSource(
        @ApplicationContext context: Context,
    ): ClientCertificateSource = KeyChainClientCertificateSource(context)

    @Provides
    @Singleton
    fun offlineCache(
        @ApplicationContext context: Context,
    ): OfflineCache = OfflineCache.create(context)

    /** Lazy: these repositories themselves depend on the accounts, which use this cleaner. */
    @Provides
    fun accountDataCleaner(
        cache: OfflineCache,
        uploads: Lazy<UploadRepository>,
        files: Lazy<DocumentFileRepository>,
    ): AccountDataCleaner =
        AccountDataCleaner { accountId ->
            uploads.get().clear(accountId)
            files.get().clear(accountId)
            cache.clear(accountId)
        }

    @Provides
    fun documentFileStorage(
        @ApplicationContext context: Context,
    ): DocumentFileStorage = DocumentFileStorage(File(context.cacheDir, "document-files"))

    @Provides
    @Singleton
    fun uploadQueue(
        @ApplicationContext context: Context,
    ): UploadQueue = UploadQueue.create(context)

    @Provides
    fun uploadStorage(
        @ApplicationContext context: Context,
    ): UploadStorage = UploadStorage(File(context.filesDir, "uploads"))

    @Provides
    @Singleton
    fun uploadScheduler(
        @ApplicationContext context: Context,
    ): UploadScheduler = WorkManagerUploadScheduler(WorkManager.getInstance(context))

    @Provides
    @Singleton
    fun uploadWorkerFactory(processor: Provider<UploadProcessor>): UploadWorkerFactory =
        UploadWorkerFactory { processor.get() }
}
