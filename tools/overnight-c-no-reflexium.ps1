param([int]$Port=25785)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
while (!(Test-Path "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json")) {Start-Sleep 5}
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$dir="$root/docs/validation/overnight-c-2026-10-31/no-reflexium"
$raw="$root/run/overnight-c/no-reflexium"
New-Item -ItemType Directory -Force $dir,$raw | Out-Null
function Snapshot($name) {
    Status|ConvertTo-Json -Depth 40|Set-Content "$dir/$name.json"
    Call screenshot @{path="$raw/$name.png"}|Out-Null
}
try {
    do {Start-Sleep 3;try {$s=Status}catch{$s=$null}} until($s)
    if ($s.game_directory -ne "$root\run") {throw 'Wrong run'}
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($s.pid)"
    if ($process.CommandLine -notmatch 'fabric.debug.disableModIds=reflexium') {throw 'Missing loader disable flag'}
    Call fullscreen @{fullscreen=1;activate=1}|Out-Null
    Call window @{monitor='primary';x=0;y=0;width=2561;height=1440}|Out-Null
    if (!$s.world_loaded) {Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null}
    Call camera @{x=200.5;y=110;z=150;yaw=90;pitch=10}|Out-Null
    Call wait @{ms=10000}|Out-Null
    Snapshot before
    for($arm=1;$arm -le 3;$arm++) {
        $pipelinesBefore=@(Select-String -Path "$root/run/logs/latest.log" -Pattern 'Creating pipeline for dimension').Count
        # Alias configured in this isolated launch; KeyMapping.click dispatches the shared R key.
        Call key @{name='key.chat';down=1;ticks=1}|Out-Null
        Call wait @{ms=10000}|Out-Null
        if ((Status).screen -ne 'none') {Call key @{scancode=41}|Out-Null}
        Snapshot "reload-$arm"
        Copy-Item "$root/run/logs/latest.log" "$dir/client.log" -Force
        $pipelinesAfter=@(Select-String -Path "$root/run/logs/latest.log" -Pattern 'Creating pipeline for dimension').Count
        if ($pipelinesAfter -le $pipelinesBefore) {throw 'Reload key did not create a new Iris pipeline; control inconclusive'}
        Call quit @{}|Out-Null
        Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null
        Call camera @{x=200.5;y=110;z=150;yaw=90;pitch=10}|Out-Null
        Call wait @{ms=10000}|Out-Null
        Snapshot "rejoin-$arm"
    }
} finally {
    & "$PSScriptRoot/iq-followup-quit.ps1" -Port $Port | Out-File "$root/docs/validation/overnight-c-2026-10-31/quit-$Port.json"
}
