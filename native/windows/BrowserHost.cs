// Window hosting only. Playback, stream requests and room/account logic remain in Java.
using System;
using System.Drawing;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal sealed class BrowserHost : Form {
    [DllImport("user32.dll")] static extern IntPtr SetParent(IntPtr child, IntPtr parent);
    [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr window, int index);
    [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr window, int index, int value);
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr window, out Rect rect);
    [DllImport("user32.dll")] static extern bool IsWindow(IntPtr window);
    [StructLayout(LayoutKind.Sequential)] struct Rect { public int Left, Top, Right, Bottom; }
    readonly IntPtr parent;
    readonly Uri page;
    readonly string profile;
    readonly WebView2 browser = new WebView2();
    readonly Timer resize = new Timer();

    BrowserHost(IntPtr parent, Uri page, string profile) {
        this.parent = parent; this.page = page; this.profile = profile;
        FormBorderStyle = FormBorderStyle.None;
        TopLevel = false;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        BackColor = Color.Black;
        browser.Dock = DockStyle.Fill;
        browser.DefaultBackgroundColor = Color.Black;
        Controls.Add(browser);
        resize.Interval = 100;
        resize.Tick += delegate {
            if (!IsWindow(parent)) { Close(); return; }
            Rect rect;
            if (GetClientRect(parent, out rect)) SetBounds(0, 0, Math.Max(1, rect.Right), Math.Max(1, rect.Bottom));
        };
        FormClosed += delegate { resize.Dispose(); browser.Dispose(); };
        Shown += async delegate { await InitializeBrowser(); };
    }
    protected override void OnHandleCreated(EventArgs args) {
        base.OnHandleCreated(args);
        SetWindowLong(Handle, -16, (GetWindowLong(Handle, -16) & ~unchecked((int)0x80000000)) | 0x40000000);
        SetParent(Handle, parent);
        Rect rect;
        if (GetClientRect(parent, out rect)) SetBounds(0, 0, Math.Max(1, rect.Right), Math.Max(1, rect.Bottom));
    }
    async Task InitializeBrowser() {
        try {
            string fixedRuntime = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "WebView2Runtime");
            if (Directory.Exists(fixedRuntime)) {
                // ZIP extraction/MSI installation may discard the build machine's ACLs.
                // Windows 11 AppContainer processes need read/execute on bundled public runtime files.
                var access = Directory.GetAccessControl(fixedRuntime);
                foreach (string sid in new string[] { "S-1-15-2-1", "S-1-15-2-2" })
                    access.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid),
                        FileSystemRights.ReadAndExecute, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                        PropagationFlags.None, AccessControlType.Allow));
                Directory.SetAccessControl(fixedRuntime, access);
            }
            browser.CreationProperties = new CoreWebView2CreationProperties {
                BrowserExecutableFolder = Directory.Exists(fixedRuntime) ? fixedRuntime : null,
                UserDataFolder = profile,
                IsInPrivateModeEnabled = true,
                AdditionalBrowserArguments = "--autoplay-policy=no-user-gesture-required"
            };
            await browser.EnsureCoreWebView2Async();
            if (IsDisposed) return;
            browser.CoreWebView2.Settings.AreDevToolsEnabled = false;
            browser.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            browser.CoreWebView2.Settings.IsStatusBarEnabled = false;
            browser.CoreWebView2.Settings.AreDefaultScriptDialogsEnabled = false;
            browser.CoreWebView2.NavigationStarting += delegate(object sender, CoreWebView2NavigationStartingEventArgs e) {
                // This host may only display the local player, never a live-room website.
                e.Cancel = !String.Equals(e.Uri, page.AbsoluteUri, StringComparison.Ordinal);
            };
            browser.CoreWebView2.NewWindowRequested += delegate(object sender, CoreWebView2NewWindowRequestedEventArgs e) { e.Handled = true; };
            browser.CoreWebView2.PermissionRequested += delegate(object sender, CoreWebView2PermissionRequestedEventArgs e) { e.State = CoreWebView2PermissionState.Deny; };
            browser.CoreWebView2.DownloadStarting += delegate(object sender, CoreWebView2DownloadStartingEventArgs e) { e.Cancel = true; };
            browser.CoreWebView2.ProcessFailed += delegate { Console.WriteLine("ERROR:PROCESS"); Console.Out.Flush(); };
            browser.CoreWebView2.Navigate(page.AbsoluteUri);
            resize.Start();
            Console.WriteLine("READY"); Console.Out.Flush();
        } catch (Exception) {
            Console.WriteLine("ERROR:WEBVIEW2"); Console.Out.Flush(); Close();
        }
    }
    [STAThread]
    static void Main(string[] args) {
        if (args.Length != 3) return;
        long handle;
        Uri page;
        if (!Int64.TryParse(args[0], out handle) || !Uri.TryCreate(args[1], UriKind.Absolute, out page)
                || page.Scheme != "http" || page.Host != "127.0.0.1") return;
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        using (var form = new BrowserHost(new IntPtr(handle), page, args[2])) {
            form.Handle.ToInt64();
            Task.Run(delegate {
                try {
                    while (true) {
                        string command = Console.ReadLine();
                        if (command == null || command == "close") {
                            if (!form.IsDisposed) form.BeginInvoke(new Action(form.Close));
                            return;
                        }
                    }
                } catch (InvalidOperationException) { }
            });
            Application.Run(form);
        }
    }
}
