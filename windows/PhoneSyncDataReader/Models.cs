namespace PhoneSyncDataReader;

public enum RecordKind
{
    Message,
    Email,
    Contact,
    Call,
    Event,
    Notification,
    Media,
    System,
    Application,
    Configuration,
    Log,
    Document,
    Generic,
}

public enum RecordFocus
{
    All,
    Images,
    Messages,
    Sms,
    Voicemails,
}

public enum ValueType
{
    String,
    Number,
    Boolean,
    Null,
}

public sealed record FlatField(string Path, string Name, ValueType ValueType, string Value);

public sealed record ParsedRecord(
    int Index,
    string RecordType,
    RecordKind Kind,
    string Title,
    string Summary,
    string? Timestamp,
    IReadOnlyList<FlatField> Fields);

public sealed record ParseSummary(int RecordCount, int FieldCount);

public sealed record FolderLabels(string FolderPath, string FolderLabel, string CollectionLabel, string RecordLabel);

public sealed record ArchiveClassification(
    string Category,
    RecordKind Kind,
    bool Sensitive,
    bool IsJson,
    bool IsImage,
    bool IsVideo);

public sealed record IndexProgress(int ProcessedFiles, int TotalFiles, string CurrentFile, int RecordCount, int FieldCount);

public sealed record IndexBuildResult(
    string ArchiveRoot,
    string DatabasePath,
    int SourceCount,
    int ArtifactCount,
    int RecordCount,
    int FieldCount,
    int ParsedFiles,
    int SensitiveFiles,
    int FailedFiles,
    string? FirstError);

public sealed record ReaderStats(int SourceCount, int ArtifactCount, int ParsedArtifactCount, int RecordCount, int FieldCount);

public sealed record FilterOption(string? Value, string Label)
{
    public override string ToString() => Label;
}

public sealed record FocusOption(RecordFocus Value, string Label);

public sealed class SelectableRecordRow
{
    public SelectableRecordRow(RecordRow record, bool selected)
    {
        Record = record;
        IsSelected = selected;
    }

    public RecordRow Record { get; }
    public bool IsSelected { get; set; }
    public long Id => Record.Id;
    public string? Timestamp => Record.Timestamp;
    public string CollectionLabel => Record.CollectionLabel;
    public RecordKind Kind => Record.Kind;
    public string Title => Record.Title;
    public string Summary => Record.Summary;
}

public sealed record RecordRow(
    long Id,
    long ArtifactId,
    string SourceId,
    string SourceName,
    string Category,
    string FolderLabel,
    string CollectionLabel,
    string RecordLabel,
    RecordKind Kind,
    string RecordType,
    int RecordIndex,
    string Title,
    string Summary,
    string? Timestamp,
    string RelativePath,
    string FullPath,
    string? ArchiveEntry,
    string JsonSource);

public sealed record RecordDetail(RecordRow Record, IReadOnlyList<FlatField> Fields);
