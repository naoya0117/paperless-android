package com.naoya0117.paperless

import android.app.Application
import androidx.work.Configuration
import com.naoya0117.paperless.core.data.UploadWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PaperlessApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var uploadWorkerFactory: UploadWorkerFactory

    /** WorkManager is initialized on demand with this (its default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(uploadWorkerFactory).build()
}
