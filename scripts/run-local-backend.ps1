$ErrorActionPreference = 'Stop'
$taskProject = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskProject
$taskToolchain = Join-Path $taskProject '.local-toolchain.ps1'
if (Test-Path -LiteralPath $taskToolchain) {
    . $taskToolchain
}
$taskCredentialsPath = Join-Path $taskProject '.local-credentials'
if (-not (Test-Path -LiteralPath $taskCredentialsPath)) {
    $taskBytes = New-Object byte[] 24
    $taskRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $taskRng.GetBytes($taskBytes)
    $taskRng.Dispose()
    $taskPassword = [Convert]::ToBase64String($taskBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    @{ username = 'admin'; password = $taskPassword } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath $taskCredentialsPath -Encoding utf8
}
$taskCredentials = Get-Content -LiteralPath $taskCredentialsPath -Raw | ConvertFrom-Json
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:BOOTSTRAP_ADMIN_USER = $taskCredentials.username
$env:BOOTSTRAP_ADMIN_PASSWORD = $taskCredentials.password
if (-not $env:APP_DEMO_SEED) {
    $env:APP_DEMO_SEED = 'true'
}
$taskJavaHome = if ($env:BACKUP_MANAGER_JAVA_HOME) {
    $env:BACKUP_MANAGER_JAVA_HOME
} else {
    $env:JAVA_HOME
}
$taskJava = if ($taskJavaHome) {
    Join-Path $taskJavaHome 'bin\java.exe'
} else {
    (Get-Command java -ErrorAction Stop).Source
}
if (-not (Test-Path -LiteralPath $taskJava)) {
    throw 'Java was not found. Install Java 21 and configure JAVA_HOME or PATH.'
}
$taskJar = Get-ChildItem -LiteralPath (Join-Path $taskProject 'target') -Filter 'backup-manager-*.jar' -File |
    Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $taskJar) {
    throw 'The backend JAR was not found. Run scripts\build-backend.ps1 first.'
}
& $taskJava -jar $taskJar.FullName
