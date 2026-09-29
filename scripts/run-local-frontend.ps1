$ErrorActionPreference = 'Stop'
$taskProject = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath (Join-Path $taskProject 'frontend')
$taskPnpm = (Get-Command pnpm -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath 'node_modules\vite\bin\vite.js')) {
    & $taskPnpm install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& $taskPnpm dev -- --host 127.0.0.1 --port 5173 --strictPort
