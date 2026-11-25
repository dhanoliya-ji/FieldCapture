package com.fieldcapture.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    @Test
    fun `parses capture nameplate`() {
        assertEquals(VoiceCommand.CaptureNameplate, VoiceCommandParser.parse("capture nameplate"))
        assertEquals(VoiceCommand.CaptureNameplate, VoiceCommandParser.parse("Capture Name Plate"))
    }

    @Test
    fun `parses capture damage with commas or spaces`() {
        val cmd1 = VoiceCommandParser.parse("capture damage, kitchen, north wall")
        assertEquals(VoiceCommand.CaptureDamage("kitchen", "north wall"), cmd1)

        val cmd2 = VoiceCommandParser.parse("capture damage kitchen north wall")
        assertEquals(VoiceCommand.CaptureDamage("kitchen", "north wall"), cmd2)

        val cmd3 = VoiceCommandParser.parse("capture damage bathroom ceiling")
        assertEquals(VoiceCommand.CaptureDamage("bathroom", "ceiling"), cmd3)
    }

    @Test
    fun `parses note with colon or space`() {
        val cmd1 = VoiceCommandParser.parse("note: subfloor soft by the cabinet")
        assertEquals(VoiceCommand.AddNote("subfloor soft by the cabinet"), cmd1)

        val cmd2 = VoiceCommandParser.parse("note water dripping from shutoff")
        assertEquals(VoiceCommand.AddNote("water dripping from shutoff"), cmd2)
    }

    @Test
    fun `returns unknown for unrecognized speech`() {
        val cmd = VoiceCommandParser.parse("hello world")
        assertTrue(cmd is VoiceCommand.Unknown)
    }
}
