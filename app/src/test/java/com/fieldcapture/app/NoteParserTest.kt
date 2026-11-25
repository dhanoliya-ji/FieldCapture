package com.fieldcapture.app.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteParserTest {

    @Test
    fun `extracts structured fields from subfloor note`() {
        val note = NoteParser.parse("subfloor soft by the cabinet")
        assertEquals("subfloor", note.component.value)
        assertEquals("soft", note.condition.value)
        assertEquals("by the cabinet", note.location.value)
        assertEquals("HIGH", note.severity.value)
        assertTrue(note.component.confidence >= 0.8f)
    }
}
