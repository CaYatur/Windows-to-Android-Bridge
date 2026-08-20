using System.Windows;
using System.Windows.Media;
using WinBridge.App.Features.Automation;

namespace WinBridge.App.Ui;

/// <summary>
/// The dialog that decides whether something a phone asked for actually runs.
///
/// It shows the fully resolved step list, never the template. A dialog reading
/// "run {{command}}" is worse than no dialog: it trains people that the answer
/// is always yes, because the text never says anything worth reading. The whole
/// value here is that the line on screen is the line about to execute.
/// </summary>
public partial class ApprovalWindow : Window
{
    private bool _allowed;

    private ApprovalWindow(ApprovalRequest request)
    {
        InitializeComponent();

        Title = request.IsSaveApproval ? "Approve automation" : "Run automation";
        LblTitle.Text = $"{request.Title}\n\"{request.AutomationName}\" — from {request.DeviceName}";

        (LblRisk.Text, RiskBorder.Background) = request.Risk switch
        {
            "dangerous" => (
                "This automation contains commands that can delete data or change how Windows starts. "
                + "Read every line below before allowing it.",
                (Brush)new SolidColorBrush(Color.FromRgb(0x8A, 0x1F, 0x2A))),
            "shell" => (
                "This automation runs shell commands on this PC with your account.",
                new SolidColorBrush(Color.FromRgb(0x7A, 0x5A, 0x14))),
            "elevated-input" => (
                "This automation controls windows, processes, files or input on this PC.",
                new SolidColorBrush(Color.FromRgb(0x2A, 0x3E, 0x6B))),
            _ => (
                "This automation only reads state and controls media.",
                new SolidColorBrush(Color.FromRgb(0x25, 0x25, 0x2C))),
        };

        LblSteps.Text = request.Lines.Count == 0
            ? "(no steps)"
            : string.Join(Environment.NewLine, request.Lines);

        // Refuse is the default focus. Someone hitting Enter on a dialog that
        // appeared while they were typing should not thereby approve a shell
        // command.
        Loaded += (_, _) => BtnRefuse.Focus();
    }

    private void OnAllow(object sender, RoutedEventArgs e)
    {
        _allowed = true;
        Close();
    }

    private void OnRefuse(object sender, RoutedEventArgs e)
    {
        _allowed = false;
        Close();
    }

    /// <summary>Shows the dialog on the UI thread and resolves once the user decides.</summary>
    public static Task<bool> AskAsync(ApprovalRequest request)
    {
        var application = System.Windows.Application.Current;
        if (application is null) return Task.FromResult(false);

        return application.Dispatcher.InvokeAsync(() =>
        {
            var window = new ApprovalWindow(request);

            // Shown modelessly with ShowDialog on purpose: the dialog has to
            // block on the user, but the app must keep serving heartbeats, so
            // the wait happens on the caller side, not on the network loop.
            window.ShowDialog();
            return window._allowed;
        }).Task;
    }
}
