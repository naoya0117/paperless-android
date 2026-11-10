package com.naoya0117.paperless.core.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Schedules uploads with WorkManager: one unique work per upload, run when a network is available. */
class WorkManagerUploadScheduler(
    private val workManager: WorkManager,
) : UploadScheduler {
    override fun schedule(
        uploadId: String,
        replace: Boolean,
    ) {
        val request =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(UploadWorker.UPLOAD_ID to uploadId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                // Started at once when possible (e.g. right after the user shared a file).
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(workName(uploadId), policy, request)
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

/**
 * Runs [UploadProcessor] for one upload. While the file is sent it runs in the foreground (with a
 * notification), as large files can take longer than background work may run. Created by
 * [UploadWorkerFactory].
 */
class UploadWorker(
    context: Context,
    parameters: WorkerParameters,
    private val processor: UploadProcessor,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(UPLOAD_ID) ?: return Result.failure()
        val outcome =
            processor.process(id, runAttemptCount) { upload ->
                try {
                    setForeground(foregroundInfo(upload.fileName))
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Exception,
                ) {
                    // Not allowed now (e.g. started in the background on Android 12+): send in the background.
                }
            }
        return when (outcome) {
            UploadProcessor.Outcome.FINISHED -> Result.success()
            UploadProcessor.Outcome.RETRY -> Result.retry()
        }
    }

    /** Needed for expedited work before Android 12. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(fileName: String?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channelName = applicationContext.getString(R.string.upload_channel_name)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW),
        )
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(applicationContext.getString(R.string.upload_notification_title))
                .setContentText(fileName)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val UPLOAD_ID = "uploadId"
        private const val CHANNEL_ID = "uploads"
        private const val NOTIFICATION_ID = 1001
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
