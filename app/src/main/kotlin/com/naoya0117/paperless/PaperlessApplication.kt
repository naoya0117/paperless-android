package com.naoya0117.paperless

import android.app.Application
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.naoya0117.paperless.core.data.ApplicationScope
import com.naoya0117.paperless.core.data.DocumentFileRepository
import com.naoya0117.paperless.core.data.UploadRepository
import com.naoya0117.paperless.core.data.UploadWorkerFactory
import com.naoya0117.paperless.feature.documents.documentThumbnails
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PaperlessApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject lateinit var uploadWorkerFactory: UploadWorkerFactory

    @Inject lateinit var uploads: Lazy<UploadRepository>

    @Inject lateinit var documentFiles: Lazy<DocumentFileRepository>

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // After the process was killed: reschedule unfinished uploads and delete leftover files.
        appScope.launch { uploads.get().reconcile() }
    }

    /** Coil loads document thumbnails through the document file cache. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).documentThumbnails { documentFiles.get() }.build()

    /** WorkManager is initialized on demand with this (its default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(uploadWorkerFactory).build()
}
