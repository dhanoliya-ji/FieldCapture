package com.fieldcapture.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fieldcapture.app.data.local.FieldCaptureDatabase

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = FieldCaptureDatabase(applicationContext)
        val repo = UploadRepository(applicationContext, db)
        if (repo.simulateOffline) {
            return Result.retry()
        }
        repo.flushPending(isNetworkOnline = true)
        return if (db.countPending() == 0) Result.success() else Result.retry()
    }
}
