param([Parameter(Mandatory)][long]$Hwnd)
$ErrorActionPreference='Stop'
$reservation=Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json
if($reservation.owner -ne 'worker-P'){throw 'Worker P GPU reservation required'}
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class PForeground {
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
 [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
 [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a,uint b,bool attach);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr SetFocus(IntPtr h);
}
'@
$target=[IntPtr]::new($Hwnd)
$current=[PForeground]::GetCurrentThreadId()
$threads=@([PForeground]::GetWindowThreadProcessId([PForeground]::GetForegroundWindow(),[IntPtr]::Zero),[PForeground]::GetWindowThreadProcessId($target,[IntPtr]::Zero))|Select-Object -Unique
$attached=@()
try{
 foreach($thread in $threads){if($thread -ne 0 -and $thread -ne $current -and [PForeground]::AttachThreadInput($current,$thread,$true)){$attached+=$thread}}
 [PForeground]::BringWindowToTop($target)|Out-Null
 $success=[PForeground]::SetForegroundWindow($target)
 [PForeground]::SetFocus($target)|Out-Null
 @{requested=$Hwnd;success=$success;foreground=[PForeground]::GetForegroundWindow().ToInt64()}|ConvertTo-Json -Compress
}finally{foreach($thread in $attached){[PForeground]::AttachThreadInput($current,$thread,$false)|Out-Null}}
