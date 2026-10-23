$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot '../.env'
if (Test-Path -LiteralPath $target) { throw '.env already exists; preserve or edit it manually.' }
function New-DemoPassword {
 $bytes = New-Object byte[] 24
 $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
 try { $rng.GetBytes($bytes); return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant() } finally { $rng.Dispose() }
}
@("DB_PASSWORD=$(New-DemoPassword)", "ANALYTICS_PASSWORD=$(New-DemoPassword)", 'PLANNER_MODE=demo', 'OLLAMA_MODEL=qwen2.5:3b', 'OLLAMA_URL=http://host.docker.internal:11434') | Set-Content -LiteralPath $target -Encoding ascii
Write-Output 'Created .env with random local demo credentials.'
& (Join-Path $PSScriptRoot 'upgrade-env.ps1')
