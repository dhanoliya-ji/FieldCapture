package com.fieldcapture.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fieldcapture.app.data.model.JobRecord
import com.fieldcapture.app.data.model.UploadStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class PendingUploadRow(
    val id: String,
    val jobId: String,
    val captureId: String,
    val filePath: String,
    val payloadJson: String,
    val status: UploadStatus,
    val retryCount: Int,
    val lastError: String?,
    val createdAtMs: Long,
)

class FieldCaptureDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "fieldcapture.db"
        private const val DB_VERSION = 1
        private const val TABLE_JOBS = "jobs"
        private const val TABLE_UPLOADS = "pending_uploads"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_JOBS (
                job_id TEXT PRIMARY KEY,
                job_json TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_UPLOADS (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                capture_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                status TEXT NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun saveJob(job: JobRecord) {
        val values = ContentValues().apply {
            put("job_id", job.jobId)
            put("job_json", json.encodeToString(job))
            put("updated_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE_JOBS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadLatestJob(): JobRecord? {
        readableDatabase.rawQuery(
            "SELECT job_json FROM $TABLE_JOBS ORDER BY updated_at_ms DESC LIMIT 1",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return runCatching { json.decodeFromString<JobRecord>(cursor.getString(0)) }.getOrNull()
            }
        }
        return null
    }

    fun enqueueUpload(row: PendingUploadRow) {
        val values = ContentValues().apply {
            put("id", row.id)
            put("job_id", row.jobId)
            put("capture_id", row.captureId)
            put("file_path", row.filePath)
            put("payload_json", row.payloadJson)
            put("status", row.status.name)
            put("retry_count", row.retryCount)
            put("last_error", row.lastError)
            put("created_at_ms", row.createdAtMs)
        }
        writableDatabase.insertWithOnConflict(TABLE_UPLOADS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getPendingUploads(): List<PendingUploadRow> {
        val list = mutableListOf<PendingUploadRow>()
        readableDatabase.rawQuery(
            "SELECT id, job_id, capture_id, file_path, payload_json, status, retry_count, last_error, created_at_ms " +
                "FROM $TABLE_UPLOADS WHERE status != ? ORDER BY created_at_ms ASC",
            arrayOf(UploadStatus.SYNCED.name),
        ).use { c ->
            while (c.moveToNext()) {
                list += PendingUploadRow(
                    id = c.getString(0),
                    jobId = c.getString(1),
                    captureId = c.getString(2),
                    filePath = c.getString(3),
                    payloadJson = c.getString(4),
                    status = UploadStatus.valueOf(c.getString(5)),
                    retryCount = c.getInt(6),
                    lastError = c.getString(7),
                    createdAtMs = c.getLong(8),
                )
            }
        }
        return list
    }

    fun updateUploadStatus(id: String, status: UploadStatus, retryCount: Int, error: String?) {
        val values = ContentValues().apply {
            put("status", status.name)
            put("retry_count", retryCount)
            put("last_error", error)
        }
        writableDatabase.update(TABLE_UPLOADS, values, "id = ?", arrayOf(id))
    }

    fun countPending(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_UPLOADS WHERE status != ?",
            arrayOf(UploadStatus.SYNCED.name),
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
