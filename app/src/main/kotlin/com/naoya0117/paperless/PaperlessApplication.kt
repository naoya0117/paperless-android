package com.naoya0117.paperless

import android.app.Application
import androidx.work.Configuration
import com.naoya0117.paperless.core.data.UploadRepository
import com.naoya0117.paperless.core.data.UploadWorkerFactory
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PaperlessApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var uploadWorkerFactory: UploadWorkerFactory

    @Inject lateinit var uploads: Lazy<UploadRepository>

    /**
     * Work that may outlive any screen; lives as long as the process. Failures are not fatal: this is only
     * housekeeping, repeated at the next start.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

    override fun onCreate() {
        super.onCreate()
        // After the process was killed: reschedule unfinished uploads and delete leftover files.
        appScope.launch { uploads.get().reconcile() }
    }

    /** WorkManager is initialized on demand with this (its default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(uploadWorkerFactory).build()
}
