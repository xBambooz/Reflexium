param([int]$Port=25785)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
while (!(Test-Path "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json")) {Start-Sleep 5}
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$dir="$root/docs/validation/overnight-c-2026-10-31/no-reflexium"
$raw="$root/run/overnight-c/no-reflexium"
New-Item -ItemType Directory -Force $dir,$raw | Out-Null
function Snapshot($name) {
    $current=Status
    if (!$current.world_loaded -or $current.screen -ne 'none' -or !$current.window.fullscreen.borderless -or $current.window.fullscreen.exclusive_fullscreen) {throw 'Invalid reload-control scene/window'}
    $current|ConvertTo-Json -Depth 40|Set-Content "$dir/$name.json"
    Call screenshot @{path="$raw/$name.png"}|ConvertTo-Json -Depth 40|Set-Content "$dir/$name-screenshot.json"
}
try {
    do {Start-Sleep 3;try {$s=Status}catch{$s=$null}} until($s)
    if ($s.game_directory -ne "$root\run") {throw 'Wrong run'}
    $launch=Get-Content "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json" -Raw|ConvertFrom-Json
    if ([IO.Path]::GetFullPath($launch.project) -ne [IO.Path]::GetFullPath('C:/Users/Ryan/source/repos/mc-agent-harness/examples/empty-project') -or $s.plugins.reflexium -or (Get-ChildItem "$root/run/mods" -Filter '*reflexium*.jar')) {throw 'Reflexium absence control not established'}
    @{project=$launch.project;reflexium_plugin_present=[bool]$s.plugins.reflexium;reflexium_jar_present=$false}|ConvertTo-Json|Set-Content "$dir/absence-control.json"
    if (!$s.world_loaded) {Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null}
    Call command @{command='gamemode spectator @a'}|Out-Null
    Call command @{command='tick freeze'}|Out-Null
    Call command @{command='time set 9000'}|Out-Null
    Call wait @{ms=10000}|Out-Null
    Call fullscreen @{fullscreen=1;activate=1}|Out-Null
    Call window @{monitor='primary';x=0;y=0;width=2561;height=1440}|Out-Null
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
        Call command @{command='gamemode spectator @a'}|Out-Null
        Call command @{command='tick freeze'}|Out-Null
        Call camera @{x=200.5;y=110;z=150;yaw=90;pitch=10}|Out-Null
        Call wait @{ms=10000}|Out-Null
        Snapshot "rejoin-$arm"
    }
} finally {
    & "$PSScriptRoot/iq-followup-quit.ps1" -Port $Port | Out-File "$root/docs/validation/overnight-c-2026-10-31/quit-$Port.json"
}
