using System.IO;
using Newtonsoft.Json;
using System.Security.Cryptography;
using System.Text;

namespace PhoneSyncDataReader;

public static class JsonRecordFlattener
{
    public static string CanonicalHash(ParsedRecord record)
    {
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (var field in record.Fields
                     .OrderBy(field => field.Path, StringComparer.Ordinal)
                     .ThenBy(field => field.ValueType)
                     .ThenBy(field => field.Value, StringComparer.Ordinal))
        {
            foreach (var value in new[] { field.Path, field.ValueType.ToString(), field.Value })
            {
                var bytes = Encoding.UTF8.GetBytes(value);
                var length = Encoding.ASCII.GetBytes(bytes.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));
                digest.AppendData(length);
                digest.AppendData(new byte[] { 0 });
                digest.AppendData(bytes);
                digest.AppendData(new byte[] { 0 });
            }
        }
        return Convert.ToHexString(digest.GetHashAndReset()).ToLowerInvariant();
    }

    public static ParseSummary Flatten(TextReader input, RecordKind kind, string defaultRecordType, Action<ParsedRecord> onRecord)
    {
        ArgumentNullException.ThrowIfNull(input);
        ArgumentNullException.ThrowIfNull(onRecord);
        using var reader = new JsonTextReader(input)
        {
            DateParseHandling = DateParseHandling.None,
            FloatParseHandling = FloatParseHandling.Decimal,
            MaxDepth = MaxDepth + 1,
            SupportMultipleContent = false,
        };
        if (!reader.Read()) return new(0, 0);

        var recordCount = 0;
        var fieldCount = 0;
        void Emit(string recordType, List<FlatField> fields)
        {
            if (fields.Count == 0) return;
            onRecord(BuildRecord(recordCount, recordType, kind, fields));
            recordCount++;
            fieldCount += fields.Count;
        }

        switch (reader.TokenType)
        {
            case JsonToken.StartArray:
                while (reader.Read() && reader.TokenType != JsonToken.EndArray)
                {
                    var fields = new List<FlatField>();
                    ReadCurrentValue(reader, "value", fields, 0);
                    Emit(defaultRecordType, fields);
                }
                break;
            case JsonToken.StartObject:
                var rootFields = new List<FlatField>();
                while (reader.Read() && reader.TokenType != JsonToken.EndObject)
                {
                    if (reader.TokenType != JsonToken.PropertyName) continue;
                    var name = Convert.ToString(reader.Value) ?? "value";
                    if (!reader.Read()) break;
                    if (reader.TokenType == JsonToken.StartArray)
                    {
                        while (reader.Read() && reader.TokenType != JsonToken.EndArray)
                        {
                            var fields = new List<FlatField>(rootFields);
                            ReadCurrentValue(reader, "value", fields, 0);
                            Emit(name, fields);
                        }
                    }
                    else
                    {
                        ReadCurrentValue(reader, name, rootFields, 0);
                    }
                }
                Emit(defaultRecordType, rootFields);
                break;
            default:
                var scalarFields = new List<FlatField>();
                ReadCurrentValue(reader, "value", scalarFields, 0);
                Emit(defaultRecordType, scalarFields);
                break;
        }

        return new(recordCount, fieldCount);
    }

    private static void ReadCurrentValue(JsonTextReader reader, string path, List<FlatField> fields, int depth)
    {
        if (depth > MaxDepth || fields.Count >= MaxFieldsPerRecord)
        {
            reader.Skip();
            return;
        }

        switch (reader.TokenType)
        {
            case JsonToken.StartObject:
                while (reader.Read() && reader.TokenType != JsonToken.EndObject)
                {
                    if (reader.TokenType != JsonToken.PropertyName) continue;
                    var name = Convert.ToString(reader.Value) ?? "value";
                    if (!reader.Read()) break;
                    ReadCurrentValue(reader, string.IsNullOrWhiteSpace(path) ? name : $"{path}.{name}", fields, depth + 1);
                }
                break;
            case JsonToken.StartArray:
                var index = 0;
                while (reader.Read() && reader.TokenType != JsonToken.EndArray)
                {
                    ReadCurrentValue(reader, $"{path}[{index}]", fields, depth + 1);
                    index++;
                }
                break;
            case JsonToken.String:
            case JsonToken.Date:
            case JsonToken.Bytes:
                AddField(fields, path, ValueType.String, Convert.ToString(reader.Value) ?? string.Empty);
                break;
            case JsonToken.Integer:
            case JsonToken.Float:
                AddField(fields, path, ValueType.Number, Convert.ToString(reader.Value, System.Globalization.CultureInfo.InvariantCulture) ?? string.Empty);
                break;
            case JsonToken.Boolean:
                AddField(fields, path, ValueType.Boolean, Convert.ToBoolean(reader.Value).ToString().ToLowerInvariant());
                break;
            case JsonToken.Null:
            case JsonToken.Undefined:
                AddField(fields, path, ValueType.Null, string.Empty);
                break;
        }
    }

    private static void AddField(List<FlatField> fields, string path, ValueType type, string value)
    {
        if (fields.Count >= MaxFieldsPerRecord) return;
        var safePath = path[..Math.Min(path.Length, MaxPathChars)];
        var tail = safePath.Split('.').LastOrDefault() ?? "value";
        var bracket = tail.IndexOf('[');
        if (bracket >= 0) tail = tail[..bracket];
        fields.Add(new(safePath, string.IsNullOrWhiteSpace(tail) ? "value" : tail, type, value[..Math.Min(value.Length, MaxValueChars)]));
    }

    private static ParsedRecord BuildRecord(int index, string recordType, RecordKind kind, IReadOnlyList<FlatField> fields)
    {
        var byName = fields.Where(f => !string.IsNullOrWhiteSpace(f.Value))
            .GroupBy(f => Normalize(f.Name))
            .ToDictionary(group => group.Key, group => group.First());
        var title = FirstValue(byName, TitleFields) ?? FirstValue(byName, AddressFields) ?? $"Record {index + 1}";
        var summary = FirstValue(byName, SummaryFields) ?? fields.FirstOrDefault(f => !string.IsNullOrWhiteSpace(f.Value))?.Value ?? "No displayable values";
        var timestamp = FirstValue(byName, TimestampFields);
        return new(
            index,
            string.IsNullOrWhiteSpace(recordType) ? kind.ToString() : Trim(recordType, MaxRecordTypeChars),
            kind,
            Trim(title, MaxTitleChars),
            Trim(summary, MaxSummaryChars),
            timestamp is null ? null : Trim(timestamp, MaxTimestampChars),
            fields);
    }

    private static string? FirstValue(IReadOnlyDictionary<string, FlatField> values, IEnumerable<string> names) =>
        names.Select(name => values.TryGetValue(name, out var value) ? value.Value : null).FirstOrDefault(value => !string.IsNullOrWhiteSpace(value));

    private static string Normalize(string value) => new(value.ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());
    private static string Trim(string value, int max) => value[..Math.Min(value.Length, max)];

    private static readonly string[] TitleFields = { "title", "subject", "name", "displayname", "contactname", "sendername", "fromname" };
    private static readonly string[] AddressFields = { "sender", "from", "recipient", "to", "address", "phonenumber", "number", "email" };
    private static readonly string[] SummaryFields = { "body", "message", "text", "content", "preview", "description", "caption", "snippet" };
    private static readonly string[] TimestampFields = { "timestamp", "datetime", "date", "time", "sentat", "receivedat", "createdat", "modifiedat" };

    private const int MaxDepth = 64;
    private const int MaxFieldsPerRecord = 4096;
    private const int MaxPathChars = 1024;
    private const int MaxValueChars = 262144;
    private const int MaxRecordTypeChars = 256;
    private const int MaxTitleChars = 512;
    private const int MaxSummaryChars = 4096;
    private const int MaxTimestampChars = 256;
}
