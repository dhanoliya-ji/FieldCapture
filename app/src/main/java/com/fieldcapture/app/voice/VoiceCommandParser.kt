package com.fieldcapture.app.voice

sealed class VoiceCommand {
    data object CaptureNameplate : VoiceCommand()
    data class CaptureDamage(val room: String, val surface: String) : VoiceCommand()
    data class AddNote(val text: String) : VoiceCommand()
    data class Unknown(val rawTranscript: String) : VoiceCommand()
}

object VoiceCommandParser {

    private val KNOWN_SURFACES = listOf(
        "north wall", "south wall", "east wall", "west wall",
        "front wall", "back wall", "left wall", "right wall",
        "subfloor", "ceiling", "floor", "wall", "cabinet",
        "baseboard", "trim", "closet", "cupboard", "pipe", "sink",
    )

    private val KNOWN_ROOMS = listOf(
        "kitchen", "bathroom", "master bath", "half bath", "basement",
        "utility room", "laundry room", "laundry", "garage", "attic",
        "hallway", "living room", "bedroom", "dining room", "closet", "mechanical room",
    )

    fun parse(rawTranscript: String): VoiceCommand {
        val cleaned = rawTranscript.trim()
        if (cleaned.isEmpty()) return VoiceCommand.Unknown(rawTranscript)

        val lower = cleaned.lowercase()
            .replace("name plate", "nameplate")
            .replace("sub floor", "subfloor")

        // 1. "capture nameplate"
        if (lower.startsWith("capture nameplate") || lower.contains("capture nameplate")) {
            return VoiceCommand.CaptureNameplate
        }

        // 2. "note: [free text]" or "note [free text]"
        if (lower.startsWith("note:") || lower.startsWith("note ")) {
            val noteBody = cleaned
                .replaceFirst(Regex("(?i)^note\\s*:?\\s*"), "")
                .trim()
            return if (noteBody.isNotEmpty()) {
                VoiceCommand.AddNote(noteBody)
            } else {
                VoiceCommand.Unknown(rawTranscript)
            }
        }

        // 3. "capture damage [room] [surface]"
        if (lower.startsWith("capture damage")) {
            val remainder = cleaned
                .replaceFirst(Regex("(?i)^capture\\s+damage\\s*[:,]?\\s*"), "")
                .trim()

            if (remainder.isEmpty()) {
                return VoiceCommand.CaptureDamage(room = "unspecified", surface = "unspecified")
            }

            // Comma-separated: "kitchen, north wall"
            if (remainder.contains(",")) {
                val parts = remainder.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val room = parts.getOrNull(0) ?: "unspecified"
                val surface = parts.drop(1).joinToString(" ").ifBlank { "unspecified" }
                return VoiceCommand.CaptureDamage(room = room, surface = surface)
            }

            val remLower = remainder.lowercase()
            val matchedSurface = KNOWN_SURFACES.firstOrNull { remLower.endsWith(it) || remLower.contains(" $it") }
            if (matchedSurface != null) {
                val idx = remLower.lastIndexOf(matchedSurface)
                val roomPart = remainder.substring(0, idx).trim().trimEnd(',', '-').ifBlank {
                    KNOWN_ROOMS.firstOrNull { remLower.contains(it) } ?: "unspecified"
                }
                return VoiceCommand.CaptureDamage(room = roomPart, surface = matchedSurface)
            }

            val matchedRoom = KNOWN_ROOMS.firstOrNull { remLower.startsWith(it) }
            if (matchedRoom != null) {
                val surfacePart = remainder.substring(matchedRoom.length).trim().ifBlank { "unspecified" }
                return VoiceCommand.CaptureDamage(room = matchedRoom, surface = surfacePart)
            }

            val tokens = remainder.split(Regex("\\s+"))
            return if (tokens.size >= 2) {
                VoiceCommand.CaptureDamage(
                    room = tokens.first(),
                    surface = tokens.drop(1).joinToString(" "),
                )
            } else {
                VoiceCommand.CaptureDamage(room = tokens.first(), surface = "unspecified")
            }
        }

        return VoiceCommand.Unknown(rawTranscript)
    }
}
