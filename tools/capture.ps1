param(
    [string]$Label = "test",
    [string]$Path = "screenshots/test.png"
)
$session = Get-Content -Raw "C:\Users\Ryan\source\repos\mc-agent-harness\instances\25601\session.json" | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($session.token)" }
$body = @{ path = $Path } | ConvertTo-Json
$res = Invoke-RestMethod "http://127.0.0.1:25601/screenshot" -Method Post -Headers $headers -ContentType "application/json" -Body $body -TimeoutSec 30
$res | ConvertTo-Json
