using System.Runtime.ExceptionServices;
using System.Windows.Interop;
using PhoneSyncDataReader;

namespace PhoneSyncDataReader.Tests;

public sealed class MainWindowTests
{
    [Fact]
    public void MainWindowLoadsAndCreatesNativeHandle()
    {
        Exception? failure = null;
        nint handle = 0;
        var visible = false;
        using var completed = new ManualResetEventSlim();
        var thread = new Thread(() =>
        {
            try
            {
                var application = new App();
                application.InitializeComponent();
                var window = new MainWindow();
                window.Show();
                handle = new WindowInteropHelper(window).EnsureHandle();
                visible = window.IsVisible;
                window.Close();
            }
            catch (Exception exception)
            {
                failure = exception;
            }
            finally
            {
                completed.Set();
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();

        Assert.True(completed.Wait(TimeSpan.FromSeconds(15)), "MainWindow did not initialize within 15 seconds.");
        thread.Join();
        if (failure is not null) ExceptionDispatchInfo.Capture(failure).Throw();
        Assert.True(visible);
        Assert.NotEqual(0, handle);
    }
}