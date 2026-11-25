package com.fieldcapture.app.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class JobRecordSerializationTest {

    @Test
    fun `round-trips JobRecord JSON cleanly`() {
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val record = JobRecord(
            jobId = "job-001",
            createdAtIso = "2026-11-25T10:00:00Z",
            updatedAtIso = "2026-11-25T10:05:00Z",
            estimatedSubtotalUsd = 420.50f,
        )
        val encoded = json.encodeToString(record)
        val decoded = json.decodeFromString<JobRecord>(encoded)
        assertEquals(record.jobId, decoded.jobId)
        assertEquals(record.estimatedSubtotalUsd, decoded.estimatedSubtotalUsd)
    }
}
