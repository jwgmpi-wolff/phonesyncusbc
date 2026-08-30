package com.jerrywolff.phonesyncusbc.data

import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonArtifactFlattenerTest {
    @Test
    fun `flattens root message array into searchable records`() {
        val records = mutableListOf<FlattenedJsonRecord>()
        val summary = JsonArtifactFlattener.flatten(
            StringReader(
                """[
                    {"sender":"Ada","body":"First message","sentAt":"2026-08-15T10:00:00Z"},
                    {"sender":"Grace","body":"Second message","attachments":[{"name":"photo.jpg"}]}
                ]""".trimIndent(),
            ),
            ConsentCategory.SMS_EXPORTS,
            "messages",
            records::add,
        )

        assertEquals(2, summary.recordCount)
        assertEquals(ParsedRecordKind.MESSAGE, records.first().recordKind)
        assertEquals("Ada", records.first().title)
        assertEquals("First message", records.first().summary)
        assertEquals("2026-08-15T10:00:00Z", records.first().timestamp)
        assertTrue(records[1].fields.any { it.path == "value.attachments[0].name" })
    }

    @Test
    fun `streams records from a named export array and retains root metadata`() {
        val records = mutableListOf<FlattenedJsonRecord>()
        JsonArtifactFlattener.flatten(
            StringReader(
                """{"account":"primary","messages":[{"from":"A","text":"Hello"},{"from":"B","text":"Bye"}]}""",
            ),
            ConsentCategory.CHAT_EXPORTS,
            "export",
            records::add,
        )

        assertEquals(3, records.size)
        assertEquals("messages", records.first().recordType)
        assertTrue(records.first().fields.any { it.path == "account" && it.value == "primary" })
        assertEquals("A", records.first().title)
        assertEquals("Hello", records.first().summary)
    }

    @Test
    fun `rejects password artifacts before reading content`() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonArtifactFlattener.flatten(
                StringReader("{\"password\":\"secret\"}"),
                ConsentCategory.PASSWORD_EXPORTS,
                "credentials",
            ) { }
        }
    }

    @Test
    fun `canonical hash ignores object field order and detects value changes`() {
        fun parse(json: String): FlattenedJsonRecord {
            lateinit var record: FlattenedJsonRecord
            JsonArtifactFlattener.flatten(
                StringReader(json),
                ConsentCategory.SMS_EXPORTS,
                "message",
            ) { record = it }
            return record
        }

        val first = canonicalRecordHash(parse("{\"sender\":\"Ada\",\"body\":\"Hello\"}"))
        val reordered = canonicalRecordHash(parse("{\"body\":\"Hello\",\"sender\":\"Ada\"}"))
        val changed = canonicalRecordHash(parse("{\"sender\":\"Ada\",\"body\":\"Goodbye\"}"))

        assertEquals(first, reordered)
        assertTrue(first != changed)
    }
}