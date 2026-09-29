$ErrorActionPreference = 'Stop'
$taskProject = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskProject
$taskToolchain = Join-Path $taskProject '.local-toolchain.ps1'
if (Test-Path -LiteralPath $taskToolchain) {
    . $taskToolchain
}
if ($env:BACKUP_MANAGER_JAVA_HOME) {
    $env:JAVA_HOME = $env:BACKUP_MANAGER_JAVA_HOME
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}
$taskMavenArgs = @('clean', 'package')
if ($env:BACKUP_MANAGER_MAVEN_REPO) {
    $taskMavenArgs = @("-Dmaven.repo.local=$env:BACKUP_MANAGER_MAVEN_REPO") + $taskMavenArgs
}
& (Join-Path $taskProject 'mvnw.cmd') @taskMavenArgs
exit $LASTEXITCODE
