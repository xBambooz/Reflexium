param([int]$Port=25721)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$s=Status
$session=Get-Content "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json"|ConvertFrom-Json
$p=Get-CimInstance Win32_Process -Filter "ProcessId=$($s.pid)"
if($s.pid -ne $session.game_pid -or $s.game_directory -ne $session.game_directory -or !$p.CommandLine.Contains($session.jar) -or !$p.CommandLine.Contains($session.game_directory)){throw 'quit ownership mismatch'}
Call quit @{exit=$true}|Out-Null
Add-Type 'using System; using System.Runtime.InteropServices; public static class FollowupFocus { [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr w); [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow(); }'
$restored=[FollowupFocus]::SetForegroundWindow([IntPtr]::new($session.foreground_before))
@{pid=$s.pid;previous_foreground=$session.foreground_before;restored=$restored;foreground=[FollowupFocus]::GetForegroundWindow().ToInt64()}|ConvertTo-Json
