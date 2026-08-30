using Microsoft.Data.Sqlite;

namespace PhoneSyncDataReader;

public sealed class ArtifactRepository : IDisposable
{
    private readonly SqliteConnection _connection;

    public ArtifactRepository(string databasePath)
    {
        _connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = databasePath,
            Mode = SqliteOpenMode.ReadOnly,
            Cache = SqliteCacheMode.Shared,
            Pooling = false,
        }.ToString());
        _connection.Open();
    }

    public ReaderStats GetStats() => new(
        Scalar("SELECT COUNT(*) FROM sources"),
        Scalar("SELECT COUNT(*) FROM artifacts"),
        Scalar("SELECT COUNT(*) FROM artifacts WHERE parse_status='PARSED'"),
        Scalar("SELECT COUNT(*) FROM records"),
        Scalar("SELECT COUNT(*) FROM fields"));

    public IReadOnlyList<FilterOption> GetSources()
    {
        using var command = _connection.CreateCommand();
        command.CommandText = "SELECT source_id, display_name FROM sources ORDER BY display_name COLLATE NOCASE";
        using var reader = command.ExecuteReader();
        var result = new List<FilterOption> { new(null, "All sources") };
        while (reader.Read()) result.Add(new(reader.GetString(0), reader.GetString(1)));
        return result;
    }

    public IReadOnlyList<FilterOption> GetKinds(string? sourceId)
    {
        using var command = _connection.CreateCommand();
        command.CommandText = "SELECT DISTINCT record_kind FROM records" + (sourceId is null ? string.Empty : " WHERE source_id=$source") + " ORDER BY record_kind";
        if (sourceId is not null) command.Parameters.AddWithValue("$source", sourceId);
        using var reader = command.ExecuteReader();
        var result = new List<FilterOption> { new(null, "All types") };
        while (reader.Read()) result.Add(new(reader.GetString(0), Friendly(reader.GetString(0))));
        return result;
    }

    public IReadOnlyList<RecordRow> Search(
        string query,
        string? sourceId,
        string? kind,
        RecordFocus focus = RecordFocus.All,
        IReadOnlyCollection<long>? recordIds = null,
        int limit = 500)
    {
        var conditions = new List<string>();
        using var command = _connection.CreateCommand();
        if (!string.IsNullOrWhiteSpace(sourceId))
        {
            conditions.Add("r.source_id=$source");
            command.Parameters.AddWithValue("$source", sourceId);
        }
        if (!string.IsNullOrWhiteSpace(kind))
        {
            conditions.Add("r.record_kind=$kind");
            command.Parameters.AddWithValue("$kind", kind);
        }
        switch (focus)
        {
            case RecordFocus.Images:
                var imageConditions = ImageExtensions.Select((_, index) => $"LOWER(COALESCE(r.archive_entry,a.relative_path)) LIKE $image{index}").ToArray();
                conditions.Add("(" + string.Join(" OR ", imageConditions) + ")");
                for (var index = 0; index < ImageExtensions.Length; index++)
                    command.Parameters.AddWithValue($"$image{index}", $"%.{ImageExtensions[index]}");
                break;
            case RecordFocus.Messages:
                conditions.Add("r.record_kind='Message' AND r.category NOT IN ('SMS_EXPORTS','VOICEMAIL_EXPORTS')");
                break;
            case RecordFocus.Sms:
                conditions.Add("r.category='SMS_EXPORTS'");
                break;
            case RecordFocus.Voicemails:
                conditions.Add("r.category='VOICEMAIL_EXPORTS'");
                break;
        }
        if (recordIds is not null)
        {
            var ids = recordIds.Take(900).ToArray();
            if (ids.Length == 0)
            {
                conditions.Add("1=0");
            }
            else
            {
                var parameters = ids.Select((_, index) => $"$record{index}").ToArray();
                conditions.Add("r.id IN (" + string.Join(',', parameters) + ")");
                for (var index = 0; index < ids.Length; index++) command.Parameters.AddWithValue(parameters[index], ids[index]);
            }
        }
        if (!string.IsNullOrWhiteSpace(query))
        {
            conditions.Add("(r.search_text LIKE $query ESCAPE '\\' OR EXISTS(SELECT 1 FROM fields f WHERE f.record_id=r.id AND f.text_value LIKE $query ESCAPE '\\'))");
            command.Parameters.AddWithValue("$query", $"%{EscapeLike(query.Trim())}%");
        }
        var where = conditions.Count == 0 ? string.Empty : "WHERE " + string.Join(" AND ", conditions);
        command.CommandText = $"""
                 SELECT r.id,r.artifact_id,r.source_id,s.display_name,r.category,r.folder_label,r.collection_label,r.record_label,r.record_kind,r.record_type,r.record_index,
                   r.title,r.summary,r.timestamp_text,a.relative_path,a.full_path,r.archive_entry,r.json_source
            FROM records r
            JOIN artifacts a ON a.id=r.artifact_id
            JOIN sources s ON s.source_id=r.source_id
            {where}
            ORDER BY COALESCE(r.timestamp_text,'') DESC,r.id DESC
            LIMIT $limit
            """;
        command.Parameters.AddWithValue("$limit", Math.Clamp(limit, 1, 5000));
        using var reader = command.ExecuteReader();
        var result = new List<RecordRow>();
        while (reader.Read()) result.Add(ReadRow(reader));
        return result;
    }

    public RecordDetail? GetDetail(long recordId)
    {
        using var recordCommand = _connection.CreateCommand();
        recordCommand.CommandText = """
                 SELECT r.id,r.artifact_id,r.source_id,s.display_name,r.category,r.folder_label,r.collection_label,r.record_label,r.record_kind,r.record_type,r.record_index,
                   r.title,r.summary,r.timestamp_text,a.relative_path,a.full_path,r.archive_entry,r.json_source
            FROM records r
            JOIN artifacts a ON a.id=r.artifact_id
            JOIN sources s ON s.source_id=r.source_id
            WHERE r.id=$id
            """;
        recordCommand.Parameters.AddWithValue("$id", recordId);
        using var recordReader = recordCommand.ExecuteReader();
        if (!recordReader.Read()) return null;
        var row = ReadRow(recordReader);
        recordReader.Close();

        using var fieldCommand = _connection.CreateCommand();
        fieldCommand.CommandText = "SELECT field_path,field_name,value_type,text_value FROM fields WHERE record_id=$id ORDER BY field_path COLLATE NOCASE";
        fieldCommand.Parameters.AddWithValue("$id", recordId);
        using var fieldReader = fieldCommand.ExecuteReader();
        var fields = new List<FlatField>();
        while (fieldReader.Read())
        {
            fields.Add(new(fieldReader.GetString(0), fieldReader.GetString(1), Enum.Parse<ValueType>(fieldReader.GetString(2)), fieldReader.GetString(3)));
        }
        return new(row, fields);
    }

    private int Scalar(string sql)
    {
        using var command = _connection.CreateCommand();
        command.CommandText = sql;
        return Convert.ToInt32(command.ExecuteScalar(), System.Globalization.CultureInfo.InvariantCulture);
    }

    private static RecordRow ReadRow(SqliteDataReader reader) => new(
        reader.GetInt64(0), reader.GetInt64(1), reader.GetString(2), reader.GetString(3), reader.GetString(4),
        reader.GetString(5), reader.GetString(6), reader.GetString(7), Enum.Parse<RecordKind>(reader.GetString(8)),
        reader.GetString(9), reader.GetInt32(10), reader.GetString(11), reader.GetString(12),
        reader.IsDBNull(13) ? null : reader.GetString(13), reader.GetString(14), reader.GetString(15),
        reader.IsDBNull(16) ? null : reader.GetString(16), reader.GetString(17));

    private static string EscapeLike(string value) => value.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_");
    private static string Friendly(string value) => string.Concat(value.Select((character, index) => index > 0 && char.IsUpper(character) ? $" {character}" : character.ToString()));

    private static readonly string[] ImageExtensions =
    ["bmp", "dng", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp"];

    public void Dispose() => _connection.Dispose();
}
