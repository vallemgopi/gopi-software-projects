$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot '../.env'
if (-not (Test-Path -LiteralPath $target)) { throw 'Create .env with setup-env first.' }
$content = Get-Content -LiteralPath $target
foreach ($key in @('CONTROL_PASSWORD','LOCAL_DEMO_SECRET')) {
 if (-not ($content -match "^${key}=")) {
  $bytes = New-Object byte[] 32
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  Add-Content -LiteralPath $target -Encoding ascii -Value ($key + '=' + ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant())
 }
}
Write-Output 'Added missing v2 local secrets; existing values preserved.'
