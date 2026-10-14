param(
    [string]$World = "Reflexium Vitrail Smoke"
)
$session = Get-Content -Raw "C:\Users\Ryan\source\repos\mc-agent-harness\instances\25601\session.json" | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($session.token)" }
$body = @{ world = $World } | ConvertTo-Json
$res = Invoke-RestMethod "http://127.0.0.1:25601/join" -Method Post -Headers $headers -ContentType "application/json" -Body $body -TimeoutSec 60
$res | ConvertTo-Json
