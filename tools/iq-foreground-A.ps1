param([Parameter(Mandatory)][int]$Port)
$ErrorActionPreference='Stop'
$reservation=Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json
if($reservation.owner -ne 'worker-A-overnight'){throw 'Worker A GPU reservation required'}
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$s=Status
$root=Split-Path $PSScriptRoot
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($s.pid)"
if($s.pid -ne $session.game_pid -or $reservation.pid -ne $s.pid -or $s.game_directory -ne "$root\run" -or !$process.CommandLine.Contains($s.game_directory)){throw 'Foreground target ownership mismatch'}
# Same two-thread attachment used by worker P's focus.ps1 at c8b31c1.
if(!('AForeground' -as [type])){Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class AForeground {
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
 [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
 [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a,uint b,bool attach);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr SetFocus(IntPtr h);
}
'@}
$target=[IntPtr]::new($s.focus.hwnd)
$current=[AForeground]::GetCurrentThreadId()
$threads=@([AForeground]::GetWindowThreadProcessId([AForeground]::GetForegroundWindow(),[IntPtr]::Zero),[AForeground]::GetWindowThreadProcessId($target,[IntPtr]::Zero))|Select-Object -Unique
$attached=@()
try{
 foreach($thread in $threads){if($thread -ne 0 -and $thread -ne $current -and [AForeground]::AttachThreadInput($current,$thread,$true)){$attached+=$thread}}
 [AForeground]::BringWindowToTop($target)|Out-Null
 $success=[AForeground]::SetForegroundWindow($target)
 [AForeground]::SetFocus($target)|Out-Null
 @{pid=$s.pid;requested=$s.focus.hwnd;success=$success;foreground=[AForeground]::GetForegroundWindow().ToInt64();attached=$attached}|ConvertTo-Json -Compress
}finally{foreach($thread in $attached){[AForeground]::AttachThreadInput($current,$thread,$false)|Out-Null}}
