package com.fieldcapture.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fieldcapture.app.data.local.FieldCaptureDatabase
import com.fieldcapture.app.data.local.PendingUploadRow
import com.fieldcapture.app.data.model.CaptureEvidence
import com.fieldcapture.app.data.model.JobRecord
import com.fieldcapture.app.data.model.UploadStatus
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class UploadRepository(
    private val context: Context,
    private val db: FieldCaptureDatabase,
) {
    companion object {
        private const val SYNC_WORK_NAME = "fieldcapture_upload_sync"
        private const val PREFS_NAME = "fieldcapture_sync_prefs"
        private const val KEY_WEBHOOK_URL = "upload_webhook_url"
        private const val KEY_SIMULATE_OFFLINE = "simulate_offline"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _pendingCount = MutableStateFlow(db.countPending())
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    var simulateOffline: Boolean
        get() = prefs.getBoolean(KEY_SIMULATE_OFFLINE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SIMULATE_OFFLINE, value).apply()
            if (!value) scheduleSync()
        }

    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value.trim()).apply()

    fun enqueueCapture(jobId: String, capture: CaptureEvidence) {
        val row = PendingUploadRow(
            id = "upload_${capture.id}",
            jobId = jobId,
            captureId = capture.id,
            filePath = capture.imagePath,
            payloadJson = json.encodeToString(capture),
            status = UploadStatus.PENDING,
            retryCount = 0,
            lastError = null,
            createdAtMs = System.currentTimeMillis(),
        )
        db.enqueueUpload(row)
        _pendingCount.value = db.countPending()
        scheduleSync()
    }

    fun scheduleSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun flushPending(isNetworkOnline: Boolean): Int = withContext(Dispatchers.IO) {
        if (simulateOffline || !isNetworkOnline) {
            return@withContext 0
        }
        val pending = db.getPendingUploads()
        var syncedCount = 0
        val endpoint = webhookUrl

        for (row in pending) {
            db.updateUploadStatus(row.id, UploadStatus.UPLOADING, row.retryCount, null)
            val result = if (endpoint.isNotBlank()) {
                runCatching {
                    val req = Request.Builder()
                        .url(endpoint)
                        .post(row.payloadJson.toRequestBody("application/json".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    }
                }
            } else {
                // Local-verified outbox sync archive when no external server URL is configured
                runCatching {
                    val outboxDir = File(context.filesDir, "synced_outbox").apply { mkdirs() }
                    File(outboxDir, "${row.captureId}.json").writeText(row.payloadJson)
                }
            }

            if (result.isSuccess) {
                db.updateUploadStatus(row.id, UploadStatus.SYNCED, row.retryCount, null)
                syncedCount++
            } else {
                db.updateUploadStatus(
                    row.id,
                    UploadStatus.FAILED,
                    row.retryCount + 1,
                    result.exceptionOrNull()?.message ?: "Upload failed",
                )
            }
        }
        _pendingCount.value = db.countPending()
        syncedCount
    }

    fun exportJobJson(job: JobRecord): File {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, "job_${job.jobId}.json")
        file.writeText(json.encodeToString(job))
        return file
    }
}
