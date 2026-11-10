package com.naoya0117.paperless.core.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Schedules uploads with WorkManager: one unique work per upload, run when a network is available. */
class WorkManagerUploadScheduler(
    private val workManager: WorkManager,
) : UploadScheduler {
    override fun schedule(uploadId: String) {
        val request =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(UploadWorker.UPLOAD_ID to uploadId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
        workManager.enqueueUniqueWork(workName(uploadId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(uploadId: String) {
        workManager.cancelUniqueWork(workName(uploadId))
    }

    private fun workName(uploadId: String) = "upload-$uploadId"

    private companion object {
        const val TAG = "upload"
        const val BACKOFF_SECONDS = 30L
    }
}

/** Runs [UploadProcessor] for one upload. Created by [UploadWorkerFactory]. */
class UploadWorker(
    context: Context,
    parameters: WorkerParameters,
    private val processor: UploadProcessor,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(UPLOAD_ID) ?: return Result.failure()
        return when (processor.process(id)) {
            UploadProcessor.Outcome.FINISHED -> Result.success()
            UploadProcessor.Outcome.RETRY -> Result.retry()
        }
    }

    companion object {
        const val UPLOAD_ID = "uploadId"
    }
}

/** Creates [UploadWorker]s with their dependencies; register it in the WorkManager configuration. */
class UploadWorkerFactory(
    private val processor: () -> UploadProcessor,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == UploadWorker::class.java.name) {
            UploadWorker(appContext, workerParameters, processor())
        } else {
            null
        }
}
