param([int]$Port,[string]$Label)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot)
$out=Join-Path $root "build/validation/view-bob-followup/$Label-fg"
New-Item -ItemType Directory -Force $out | Out-Null
$session=Get-Content "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json" -Raw | ConvertFrom-Json
$headers=@{Authorization="Bearer $($session.token)"}
function Call($endpoint,$body=@{}) { Invoke-RestMethod "http://127.0.0.1:$Port/$endpoint" -Method Post -Headers $headers -ContentType application/json -Body ($body|ConvertTo-Json -Compress) -TimeoutSec 90 }
function Status { Invoke-RestMethod "http://127.0.0.1:$Port/status" -Headers $headers -TimeoutSec 30 }
function Setting($key,$value) { Call reflexium @{key=$key;value=[string]$value}|Out-Null }
$reservation=Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock -Raw|ConvertFrom-Json
$s=Status
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($s.pid)"
if($reservation.owner -ne 'view-bob-c9f451d0' -or $s.pid -ne $session.game_pid -or $s.game_directory -ne "$root\run" -or !$process.CommandLine.Contains($session.jar) -or !$process.CommandLine.Contains($s.game_directory)){throw 'Ownership mismatch'}
if($s.window.x -ne 2560 -or $s.window.width -ne 1920 -or $s.window.height -ne 1080){throw 'Secondary placement mismatch'}
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class BobFocus {
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,IntPtr p);
 [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
 [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a,uint b,bool attach);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr SetFocus(IntPtr h);
 public static bool Focus(IntPtr h) {
  uint self=GetCurrentThreadId(), a=GetWindowThreadProcessId(GetForegroundWindow(),IntPtr.Zero),b=GetWindowThreadProcessId(h,IntPtr.Zero);
  bool aa=a!=0&&a!=self&&AttachThreadInput(self,a,true),bb=b!=0&&b!=self&&b!=a&&AttachThreadInput(self,b,true);
  try { bool ok=SetForegroundWindow(h);SetFocus(h);return ok; }
  finally {if(bb)AttachThreadInput(self,b,false);if(aa)AttachThreadInput(self,a,false);}
 }
}
'@
Setting quality ultra_performance
Setting bob_view 1
Setting frame_generation dlss_multi
Setting fg_multiplier 3
Setting reflex_interval_us 60000
Call command @{command='gamemode survival @a'}|Out-Null
Call camera @{x=200.5;y=151;z=1.5;yaw=0;pitch=3}|Out-Null
Call wait @{ms=2000}|Out-Null
$previous=[BobFocus]::GetForegroundWindow()
@{previous=$previous.ToInt64();pid=$s.pid;recorded=(Get-Date).ToString('o')}|ConvertTo-Json|Set-Content "$out/focus-before.json"
try {
 $overlay=Get-CimInstance Win32_Process|Where-Object ProcessId -EQ 22200
 if(!$overlay -or $overlay.CommandLine -notlike '*oled-overlay.ps1*'){throw 'Overlay exited, focus skipped'}
 [BobFocus]::Focus([IntPtr]::new($s.focus.hwnd))|Out-Null
 Call wait @{ms=1000}|Out-Null
 $before=Status
 $before|ConvertTo-Json -Depth 40|Set-Content "$out/start.json"
 if(!$before.focus.os_foreground -or !$before.focus.os_input_focus){throw 'Focus failed'}
 Call key @{name='key.forward';down=$true}|Out-Null
 try {
  $wgc='C:/Users/Ryan/source/repos/Reflexium/tools/wgc-capture/bin/Release/net10.0-windows10.0.26100.0/WgcCapture.exe'
  $capture=Start-Process -FilePath $wgc -ArgumentList '--pid',([string]$s.pid),'--out',"$out/presentation-small",'--count','160','--seconds','3','--png','true','--every','1','--crop','500,280,320,180','--min-interval-ms','0' -WindowStyle Hidden -RedirectStandardOutput "$out/wgc-small-result.json" -RedirectStandardError "$out/wgc-small-error.txt" -PassThru
  for($i=0;$i -lt 3;$i++) {
   Call wait @{ms=500}|Out-Null
   Call screenshot @{path="$out/real-$i.png"}|ConvertTo-Json -Depth 40|Set-Content "$out/real-$i.json"
  }
  if(!$capture.WaitForExit(10000)){throw 'WGC capture timeout'}
 } finally { Call key @{name='key.forward';down=$false}|Out-Null }
 $after=Status
 $after|ConvertTo-Json -Depth 40|Set-Content "$out/end.json"
 if($after.plugins.reflexium.fg_generated_presents -le $before.plugins.reflexium.fg_generated_presents){throw 'No generated presents'}
} finally {
 $restored=[BobFocus]::Focus($previous)
 @{restored=$restored;previous=$previous.ToInt64();foreground=[BobFocus]::GetForegroundWindow().ToInt64()}|ConvertTo-Json|Set-Content "$out/focus-restored.json"
 Setting frame_generation off
 Setting reflex_interval_us 16667
}
