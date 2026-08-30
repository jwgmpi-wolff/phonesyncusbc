package com.jerrywolff.phonesyncusbc.data

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import java.io.Reader
import java.security.MessageDigest

enum class ParsedRecordKind {
    MESSAGE,
    EMAIL,
    CONTACT,
    CALL,
    EVENT,
    NOTIFICATION,
    MEDIA,
    SYSTEM,
    APPLICATION,
    CONFIGURATION,
    LOG,
    DOCUMENT,
    GENERIC,
}

enum class FlattenedValueType {
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
}

data class FlattenedJsonField(
    val path: String,
    val name: String,
    val valueType: FlattenedValueType,
    val value: String,
)

data class FlattenedJsonRecord(
    val recordIndex: Int,
    val recordType: String,
    val recordKind: ParsedRecordKind,
    val title: String,
    val summary: String,
    val timestamp: String?,
    val fields: List<FlattenedJsonField>,
)

data class JsonFlattenSummary(
    val recordCount: Int,
    val fieldCount: Int,
)

fun canonicalRecordHash(record: FlattenedJsonRecord): String {
    val digest = MessageDigest.getInstance("SHA-256")
    record.fields
        .sortedWith(compareBy(FlattenedJsonField::path, { it.valueType.name }, FlattenedJsonField::value))
        .forEach { field ->
            listOf(field.path, field.valueType.name, field.value).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                digest.update(0)
                digest.update(bytes)
                digest.update(0)
            }
        }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

object JsonArtifactFlattener {
    fun flatten(
        input: Reader,
        category: ConsentCategory,
        defaultRecordType: String,
        onRecord: (FlattenedJsonRecord) -> Unit,
    ): JsonFlattenSummary {
        require(category != ConsentCategory.PASSWORD_EXPORTS) {
            "Sensitive password artifacts must never be parsed."
        }
        var recordCount = 0
        var fieldCount = 0
        JsonReader(input).let { reader ->
            reader.strictness = Strictness.LENIENT
            fun emit(recordType: String, fields: List<FlattenedJsonField>) {
                if (fields.isEmpty()) return
                val record = buildRecord(recordCount, recordType, category, fields)
                onRecord(record)
                recordCount += 1
                fieldCount += fields.size
            }

            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val fields = mutableListOf<FlattenedJsonField>()
                        readValue(reader, "value", fields, 0)
                        emit(defaultRecordType, fields)
                    }
                    reader.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    val rootFields = mutableListOf<FlattenedJsonField>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val fields = rootFields.toMutableList()
                                readValue(reader, "value", fields, 0)
                                emit(name, fields)
                            }
                            reader.endArray()
                        } else {
                            readValue(reader, name, rootFields, 0)
                        }
                    }
                    reader.endObject()
                    emit(defaultRecordType, rootFields)
                }
                JsonToken.END_DOCUMENT -> Unit
                else -> {
                    val fields = mutableListOf<FlattenedJsonField>()
                    readValue(reader, "value", fields, 0)
                    emit(defaultRecordType, fields)
                }
            }
        }
        return JsonFlattenSummary(recordCount, fieldCount)
    }

    private fun readValue(
        reader: JsonReader,
        path: String,
        fields: MutableList<FlattenedJsonField>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || fields.size >= MAX_FIELDS_PER_RECORD) {
            reader.skipValue()
            return
        }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    val childPath = if (path.isBlank()) name else "$path.$name"
                    readValue(reader, childPath, fields, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var index = 0
                while (reader.hasNext()) {
                    readValue(reader, "$path[$index]", fields, depth + 1)
                    index += 1
                }
                reader.endArray()
            }
            JsonToken.STRING -> addField(fields, path, FlattenedValueType.STRING, reader.nextString())
            JsonToken.NUMBER -> addField(fields, path, FlattenedValueType.NUMBER, reader.nextString())
            JsonToken.BOOLEAN -> addField(fields, path, FlattenedValueType.BOOLEAN, reader.nextBoolean().toString())
            JsonToken.NULL -> {
                reader.nextNull()
                addField(fields, path, FlattenedValueType.NULL, "")
            }
            else -> reader.skipValue()
        }
    }

    private fun addField(
        fields: MutableList<FlattenedJsonField>,
        path: String,
        valueType: FlattenedValueType,
        value: String,
    ) {
        if (fields.size >= MAX_FIELDS_PER_RECORD) return
        val safePath = path.take(MAX_PATH_CHARS)
        fields += FlattenedJsonField(
            path = safePath,
            name = safePath.substringAfterLast('.').substringBefore('[').ifBlank { "value" },
            valueType = valueType,
            value = value.take(MAX_VALUE_CHARS),
        )
    }

    private fun buildRecord(
        index: Int,
        containerName: String,
        category: ConsentCategory,
        fields: List<FlattenedJsonField>,
    ): FlattenedJsonRecord {
        val valuesByName = fields
            .filter { it.value.isNotBlank() }
            .associateBy { normalizeName(it.name) }
        val title = firstValue(valuesByName, TITLE_FIELDS)
            ?: firstValue(valuesByName, ADDRESS_FIELDS)
            ?: "Record ${index + 1}"
        val summary = firstValue(valuesByName, SUMMARY_FIELDS)
            ?: fields.firstOrNull { it.value.isNotBlank() }?.value
            ?: "No displayable values"
        return FlattenedJsonRecord(
            recordIndex = index,
            recordType = containerName.ifBlank { category.name.lowercase() }.take(MAX_RECORD_TYPE_CHARS),
            recordKind = kindFor(category),
            title = title.take(MAX_TITLE_CHARS),
            summary = summary.take(MAX_SUMMARY_CHARS),
            timestamp = firstValue(valuesByName, TIMESTAMP_FIELDS)?.take(MAX_TIMESTAMP_CHARS),
            fields = fields,
        )
    }

    private fun firstValue(
        valuesByName: Map<String, FlattenedJsonField>,
        names: List<String>,
    ): String? = names.firstNotNullOfOrNull { valuesByName[it]?.value?.takeIf(String::isNotBlank) }

    private fun normalizeName(value: String): String = value
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private fun kindFor(category: ConsentCategory): ParsedRecordKind = when (category) {
        ConsentCategory.SMS_EXPORTS, ConsentCategory.CHAT_EXPORTS -> ParsedRecordKind.MESSAGE
        ConsentCategory.EMAIL_EXPORTS -> ParsedRecordKind.EMAIL
        ConsentCategory.CONTACTS -> ParsedRecordKind.CONTACT
        ConsentCategory.CALL_LOGS -> ParsedRecordKind.CALL
        ConsentCategory.CALENDAR -> ParsedRecordKind.EVENT
        ConsentCategory.NOTIFICATION_EXPORTS -> ParsedRecordKind.NOTIFICATION
        ConsentCategory.PHOTOS_AND_VIDEOS -> ParsedRecordKind.MEDIA
        ConsentCategory.SYSTEM_INFORMATION -> ParsedRecordKind.SYSTEM
        ConsentCategory.APPLICATION_DATA -> ParsedRecordKind.APPLICATION
        ConsentCategory.CONFIGURATION -> ParsedRecordKind.CONFIGURATION
        ConsentCategory.LOGS -> ParsedRecordKind.LOG
        ConsentCategory.DOCUMENTS, ConsentCategory.SELECTED_FOLDERS -> ParsedRecordKind.DOCUMENT
        ConsentCategory.CLOUD_ACCOUNTS -> ParsedRecordKind.GENERIC
        ConsentCategory.VOICEMAIL_EXPORTS -> ParsedRecordKind.MESSAGE
        ConsentCategory.PASSWORD_EXPORTS -> error("Password artifacts cannot be parsed.")
    }

    private val TITLE_FIELDS = listOf(
        "title", "subject", "name", "displayname", "contactname", "sendername", "fromname",
    )
    private val ADDRESS_FIELDS = listOf(
        "sender", "from", "recipient", "to", "address", "phonenumber", "number", "email",
    )
    private val SUMMARY_FIELDS = listOf(
        "body", "message", "text", "content", "preview", "description", "caption", "snippet",
    )
    private val TIMESTAMP_FIELDS = listOf(
        "timestamp", "datetime", "date", "time", "sentat", "receivedat", "createdat", "modifiedat",
    )

    private const val MAX_DEPTH = 64
    private const val MAX_FIELDS_PER_RECORD = 4_096
    private const val MAX_PATH_CHARS = 1_024
    private const val MAX_VALUE_CHARS = 262_144
    private const val MAX_RECORD_TYPE_CHARS = 256
    private const val MAX_TITLE_CHARS = 512
    private const val MAX_SUMMARY_CHARS = 4_096
    private const val MAX_TIMESTAMP_CHARS = 256
}