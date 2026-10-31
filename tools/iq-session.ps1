param([int]$Port = 25701)
$ErrorActionPreference = 'Stop'
$session = Get-Content "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json" | ConvertFrom-Json
$headers = @{Authorization="Bearer $($session.token)"}
function Call([string]$endpoint, $body = @{}) {
    $result = Invoke-RestMethod "http://127.0.0.1:$Port/$endpoint" -Method Post -Headers $headers -ContentType application/json -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 120
    if ($result.ok -eq $false) { throw ($result | ConvertTo-Json -Compress -Depth 4) }
    $result
}
function Setting([string]$key, [string]$value) { Call reflexium @{key=$key;value=$value} | Out-Null }
function Status { Invoke-RestMethod "http://127.0.0.1:$Port/status" -Headers $headers -TimeoutSec 15 }
$evidence = Join-Path (Split-Path $PSScriptRoot) 'docs/validation/iq-issues-2026-10-30'
New-Item -ItemType Directory -Force $evidence | Out-Null
function SaveStatus([string]$name) { Status | ConvertTo-Json -Depth 30 | Set-Content "$evidence/$name.json" }
function Shot([string]$name) { Call screenshot @{path="$evidence/$name.png"} | Out-Null; SaveStatus $name }
