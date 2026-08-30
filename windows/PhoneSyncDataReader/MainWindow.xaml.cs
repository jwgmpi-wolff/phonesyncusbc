using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using Microsoft.Win32;

namespace PhoneSyncDataReader;

public partial class MainWindow : Window
{
    private readonly ArchiveIndexer _indexer = new();
    private ArtifactRepository? _repository;
    private string? _archiveRoot;
    private string? _databasePath;
    private bool _busy;
    private readonly HashSet<long> _selectedRecordIds = [];
    private IReadOnlyList<SelectableRecordRow> _visibleRecords = [];
    private RecordRow? _detailRecord;
    private readonly string _previewDirectory = Path.Combine(Path.GetTempPath(), "PhoneSyncDataReader", "Preview");

    public MainWindow()
    {
        InitializeComponent();
        KindFilter.ItemsSource = new[] { new FilterOption(null, "All types") };
        KindFilter.SelectedIndex = 0;
        SourceFilter.ItemsSource = new[] { new FilterOption(null, "All sources") };
        SourceFilter.SelectedIndex = 0;
        FocusFilter.ItemsSource = new[]
        {
            new FocusOption(RecordFocus.All, "All"),
            new FocusOption(RecordFocus.Images, "Images"),
            new FocusOption(RecordFocus.Messages, "Messages"),
            new FocusOption(RecordFocus.Sms, "SMS"),
            new FocusOption(RecordFocus.Voicemails, "Voicemails"),
        };
        FocusFilter.SelectedIndex = 0;
    }

    private async void ChooseFolder_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog
        {
            Title = "Choose the archived Phone Sync recovery folder",
            Multiselect = false,
        };
        if (dialog.ShowDialog(this) != true) return;
        _archiveRoot = dialog.FolderName;
        _databasePath = ArchiveIndexer.GetDatabasePath(_archiveRoot);
        ArchivePathText.Text = _archiveRoot;
        RebuildButton.IsEnabled = true;
        OpenIndexFolderButton.IsEnabled = true;
        if (File.Exists(_databasePath))
        {
            OpenRepository();
            StatusText.Text = "Existing local index opened. Choose Build local index to refresh the archive.";
        }
        else
        {
            await BuildIndexAsync();
        }
    }

    private async void Rebuild_Click(object sender, RoutedEventArgs e) => await BuildIndexAsync();

    private async Task BuildIndexAsync()
    {
        if (_archiveRoot is null || _busy) return;
        SetBusy(true);
        try
        {
            _repository?.Dispose();
            _repository = null;
            var progress = new Progress<IndexProgress>(update =>
            {
                IndexProgress.Visibility = Visibility.Visible;
                IndexProgress.Value = update.TotalFiles == 0 ? 0 : (double)update.ProcessedFiles / update.TotalFiles;
                StatusText.Text = $"Indexing {update.ProcessedFiles:N0}/{update.TotalFiles:N0}: {update.CurrentFile} · {update.RecordCount:N0} records";
            });
            var result = await _indexer.BuildAsync(_archiveRoot, progress, CancellationToken.None);
            _databasePath = result.DatabasePath;
            OpenRepository();
            StatusText.Text = result.FailedFiles == 0
                ? $"Indexed {result.ArtifactCount:N0} unique artifacts, {result.RecordCount:N0} unique records, and {result.FieldCount:N0} fields."
                : $"Indexed {result.RecordCount:N0} records; {result.FailedFiles:N0} files failed. First error: {result.FirstError}";
        }
        catch (Exception exception)
        {
            StatusText.Text = $"Index failed: {exception.Message}";
            MessageBox.Show(this, exception.ToString(), "Index failed", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            SetBusy(false);
            IndexProgress.Visibility = Visibility.Collapsed;
        }
    }

    private void OpenRepository()
    {
        if (_databasePath is null) return;
        _repository?.Dispose();
        _repository = new ArtifactRepository(_databasePath);
        var stats = _repository.GetStats();
        StatsText.Text = $"{stats.SourceCount:N0} sources  ·  {stats.ArtifactCount:N0} files  ·  {stats.RecordCount:N0} records";
        SourceFilter.ItemsSource = _repository.GetSources();
        SourceFilter.SelectedIndex = 0;
        RefreshKinds();
        SearchRecords();
    }

    private void RefreshKinds()
    {
        if (_repository is null) return;
        KindFilter.ItemsSource = _repository.GetKinds(SelectedValue(SourceFilter));
        KindFilter.SelectedIndex = 0;
    }

    private void SearchRecords()
    {
        if (_repository is null) return;
        var selectedOnly = SelectedOnlyCheckBox.IsChecked == true ? _selectedRecordIds : null;
        var records = _repository.Search(SearchText.Text, SelectedValue(SourceFilter), SelectedValue(KindFilter), SelectedFocus(), selectedOnly);
        _visibleRecords = records.Select(record => new SelectableRecordRow(record, _selectedRecordIds.Contains(record.Id))).ToArray();
        RecordsGrid.ItemsSource = _visibleRecords;
        ResultCountText.Text = $"{records.Count:N0} shown · {_selectedRecordIds.Count:N0} selected";
        if (_visibleRecords.Count > 0) RecordsGrid.SelectedIndex = 0;
        else ClearDetail();
    }

    private void Search_Click(object sender, RoutedEventArgs e) => SearchRecords();

    private void SearchText_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) SearchRecords();
    }

    private void Filter_Changed(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_repository is null || _busy) return;
        if (ReferenceEquals(sender, SourceFilter)) RefreshKinds();
        SearchRecords();
    }

    private void SelectedOnly_Changed(object sender, RoutedEventArgs e)
    {
        if (_repository is not null) SearchRecords();
    }

    private void RecordSelection_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as System.Windows.Controls.CheckBox)?.DataContext is not SelectableRecordRow row) return;
        if (row.IsSelected) _selectedRecordIds.Add(row.Id);
        else _selectedRecordIds.Remove(row.Id);
        ResultCountText.Text = $"{_visibleRecords.Count:N0} shown · {_selectedRecordIds.Count:N0} selected";
    }

    private void SelectShown_Click(object sender, RoutedEventArgs e)
    {
        foreach (var row in _visibleRecords) { row.IsSelected = true; _selectedRecordIds.Add(row.Id); }
        RecordsGrid.Items.Refresh();
        ResultCountText.Text = $"{_visibleRecords.Count:N0} shown · {_selectedRecordIds.Count:N0} selected";
    }

    private void ClearSelection_Click(object sender, RoutedEventArgs e)
    {
        _selectedRecordIds.Clear();
        if (SelectedOnlyCheckBox.IsChecked == true) SearchRecords();
        else { foreach (var row in _visibleRecords) row.IsSelected = false; RecordsGrid.Items.Refresh(); }
    }

    private void RecordsGrid_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_repository is null || RecordsGrid.SelectedItem is not SelectableRecordRow selected) return;
        var detail = _repository.GetDetail(selected.Id);
        if (detail is null) return;
        DetailTitle.Text = detail.Record.Title;
        DetailMetadata.Text = $"{detail.Record.Kind} · {detail.Record.CollectionLabel} · {detail.Record.FolderLabel} · {detail.Record.SourceName}\n{detail.Record.RelativePath}";
        DetailSummary.Text = detail.Record.Summary;
        FieldsGrid.ItemsSource = detail.Fields;
        _detailRecord = detail.Record;
        ShowPreview(detail.Record);
        OpenMediaButton.Visibility = IsImage(detail.Record) || IsVoicemail(detail.Record) ? Visibility.Visible : Visibility.Collapsed;
        OpenMediaButton.Content = IsVoicemail(detail.Record) ? "Play voicemail" : "Open image";
    }

    private void OpenMedia_Click(object sender, RoutedEventArgs e)
    {
        if (_detailRecord is null) return;
        try
        {
            var path = MaterializeMedia(_detailRecord);
            Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
        }
        catch (Exception exception)
        {
            MessageBox.Show(this, exception.Message, "Media could not be opened", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private string MaterializeMedia(RecordRow record)
    {
        if (record.ArchiveEntry is null) return record.FullPath;
        Directory.CreateDirectory(_previewDirectory);
        var destination = Path.Combine(_previewDirectory, $"{record.Id}-{Path.GetFileName(record.ArchiveEntry)}");
        using var archive = ZipFile.OpenRead(record.FullPath);
        var entry = archive.GetEntry(record.ArchiveEntry) ?? throw new InvalidDataException("The media entry is missing from its archive.");
        using var input = entry.Open();
        using var output = File.Create(destination);
        input.CopyTo(output);
        return destination;
    }

    private void ShowPreview(RecordRow record)
    {
        PreviewImage.Source = null;
        PreviewBorder.Visibility = Visibility.Collapsed;
        if (record.Kind != RecordKind.Media) return;
        try
        {
            BitmapImage? bitmap;
            if (record.ArchiveEntry is null)
            {
                if (!ArchiveClassifier.IsImagePath(record.FullPath) || !File.Exists(record.FullPath)) return;
                using var input = File.OpenRead(record.FullPath);
                bitmap = LoadBitmap(input);
            }
            else
            {
                if (!ArchiveClassifier.IsImagePath(record.ArchiveEntry) || !File.Exists(record.FullPath)) return;
                using var archive = ZipFile.OpenRead(record.FullPath);
                var entry = archive.GetEntry(record.ArchiveEntry);
                if (entry is null) return;
                using var input = entry.Open();
                bitmap = LoadBitmap(input);
            }
            PreviewImage.Source = bitmap;
            PreviewBorder.Visibility = Visibility.Visible;
        }
        catch
        {
            PreviewImage.Source = null;
            PreviewBorder.Visibility = Visibility.Collapsed;
        }
    }

    private static BitmapImage LoadBitmap(Stream input)
    {
        var memory = new MemoryStream();
        input.CopyTo(memory);
        memory.Position = 0;
        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = memory;
        image.EndInit();
        image.Freeze();
        return image;
    }

    private void OpenIndexFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_databasePath is null) return;
        var directory = Path.GetDirectoryName(_databasePath);
        if (directory is null) return;
        Directory.CreateDirectory(directory);
        Process.Start(new ProcessStartInfo("explorer.exe", directory) { UseShellExecute = true });
    }

    private static string? SelectedValue(System.Windows.Controls.ComboBox comboBox) =>
        (comboBox.SelectedItem as FilterOption)?.Value;

    private RecordFocus SelectedFocus() => (FocusFilter.SelectedItem as FocusOption)?.Value ?? RecordFocus.All;

    private static bool IsImage(RecordRow record) => ArchiveClassifier.IsImagePath(record.ArchiveEntry ?? record.RelativePath);

    private static bool IsVoicemail(RecordRow record) => record.Category == "VOICEMAIL_EXPORTS";

    private void SetBusy(bool busy)
    {
        _busy = busy;
        ChooseFolderButton.IsEnabled = !busy;
        RebuildButton.IsEnabled = !busy && _archiveRoot is not null;
        OpenIndexFolderButton.IsEnabled = !busy && _databasePath is not null;
    }

    private void ClearDetail()
    {
        DetailTitle.Text = "Select a record";
        DetailMetadata.Text = string.Empty;
        DetailSummary.Text = string.Empty;
        FieldsGrid.ItemsSource = null;
        PreviewImage.Source = null;
        PreviewBorder.Visibility = Visibility.Collapsed;
        OpenMediaButton.Visibility = Visibility.Collapsed;
        _detailRecord = null;
    }

    protected override void OnClosed(EventArgs e)
    {
        _repository?.Dispose();
        if (Directory.Exists(_previewDirectory)) Directory.Delete(_previewDirectory, true);
        base.OnClosed(e);
    }
}
