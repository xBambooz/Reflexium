# Black OLED-care overlay for the primary display while unattended tests run.
# Covers every monitor with a borderless black window that never takes focus on its own.
# Click it, then press Esc to close. Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\oled-overlay.ps1
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

Add-Type @'
using System.Windows.Forms;
public class OledOverlay : Form {
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
