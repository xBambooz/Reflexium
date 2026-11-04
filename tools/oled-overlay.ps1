# Black OLED-care overlay for the primary display while unattended tests run.
# Covers every monitor with a borderless black window that never takes focus on its own and moves back above
# every other topmost window ten times a second, so a game window or the taskbar cannot stay above it.
# Exclusive fullscreen is the one case it cannot cover; test clients use borderless.
# Click it, then press Esc to close. Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\oled-overlay.ps1
# -SkipPrimary leaves the primary display uncovered, for test clients running fullscreen there.
param([switch]$SkipPrimary)
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;
public class OledOverlay : Form {
	[DllImport("user32.dll")]
	static extern bool SetWindowPos(IntPtr hWnd, IntPtr insertAfter, int x, int y, int cx, int cy, uint flags);
	static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
	const uint KEEP_ABOVE = 0x0001 | 0x0002 | 0x0010 | 0x0200; // SWP_NOSIZE, SWP_NOMOVE, SWP_NOACTIVATE, SWP_NOOWNERZORDER
	readonly Timer keepAbove = new Timer();
	public OledOverlay() {
		keepAbove.Interval = 100;
		keepAbove.Tick += delegate { SetWindowPos(Handle, HWND_TOPMOST, 0, 0, 0, 0, KEEP_ABOVE); };
	}
	protected override void OnShown(EventArgs e) {
		base.OnShown(e);
		keepAbove.Start();
	}
	protected override bool ShowWithoutActivation { get { return true; } }
	protected override CreateParams CreateParams {
		get {
			CreateParams p = base.CreateParams;
			p.ExStyle |= 0x08000000; // WS_EX_NOACTIVATE: never steals focus from the test window
			p.ExStyle |= 0x00000080; // WS_EX_TOOLWINDOW: no taskbar or Alt+Tab entry
			return p;
		}
	}
}
'@ -ReferencedAssemblies System.Windows.Forms

$forms = @()
foreach ($screen in [System.Windows.Forms.Screen]::AllScreens) {
	if ($SkipPrimary -and $screen.Primary) { continue }
	$form = New-Object OledOverlay
	$form.FormBorderStyle = 'None'
	$form.StartPosition = 'Manual'
	$form.Bounds = $screen.Bounds
	$form.BackColor = [System.Drawing.Color]::Black
	$form.TopMost = $true
	$form.KeyPreview = $true
	$form.Add_KeyDown({ if ($_.KeyCode -eq 'Escape') { [System.Windows.Forms.Application]::Exit() } })
	$form.Add_MouseDown({ $this.Activate() })
	$form.Show()
	$forms += $form
}
[System.Windows.Forms.Application]::Run()
