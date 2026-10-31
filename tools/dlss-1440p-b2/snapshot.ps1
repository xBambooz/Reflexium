param([string]$OutputDirectory = "$PSScriptRoot/../../docs/validation/dlss-1440p-perf-2026-10-30/b3")
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$real="$env:APPDATA/ModrinthApp/profiles/Fabric 26.3"
$copy="$env:APPDATA/ModrinthApp/profiles/Fabric 26.3 RxRound17"
function Inventory($dir) {
 @(Get-ChildItem -LiteralPath "$dir/mods" -File | Where-Object {$_.Name -match '\.jar(\.disabled)?$'} | ForEach-Object {
  $zip=[IO.Compression.ZipFile]::OpenRead($_.FullName)
  try { $entry=$zip.GetEntry('fabric.mod.json'); $meta=$null
   if($entry){$reader=[IO.StreamReader]::new($entry.Open());try{$meta=$reader.ReadToEnd() | ConvertFrom-Json}finally{$reader.Dispose()}}
   [pscustomobject]@{file=$_.Name;enabled=$_.Name.EndsWith('.jar');id=$meta.id;version=$meta.version;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
  } finally {$zip.Dispose()}
 })
}
$a=Inventory $real; $b=Inventory $copy
$diff=@(Compare-Object ($a | ForEach-Object {"$($_.file)|$($_.id)|$($_.version)|$($_.sha256)"}) ($b | ForEach-Object {"$($_.file)|$($_.id)|$($_.version)|$($_.sha256)"}))
@{real=$a;copy=$b;differences=$diff;captured=(Get-Date -Format o)} | ConvertTo-Json -Depth 8 | Set-Content "$OutputDirectory/mod-inventory.json"
$targets=@("$copy/shaderpacks/ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5","$copy/shaderpacks/ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5.txt","$copy/config","$copy/options.txt",'C:/Users/Ryan/Documents/Benchmark Mod RxRound17/settings.json','C:/Users/Ryan/Documents/Benchmark Mod RxRound17/scenes/benchmark-route')
$hashes=@(foreach($t in $targets){if(Test-Path -LiteralPath $t){Get-ChildItem -LiteralPath $t -File -Recurse | ForEach-Object {[pscustomobject]@{path=$_.FullName;size=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}}}})
$hashes | ConvertTo-Json -Depth 4 | Set-Content "$OutputDirectory/input-hashes.json"
"Inventory real=$($a.Count), copy=$($b.Count), differences=$($diff.Count), hashed=$($hashes.Count)"
