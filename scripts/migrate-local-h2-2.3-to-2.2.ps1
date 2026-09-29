[CmdletBinding()]
param(
    [string]$DatabaseBasePath,
    [string]$SourceH2Jar,
    [string]$TargetH2Jar,
    [string]$SourceJavaPath,
    [string]$TargetJavaPath,
    [string]$User = 'sa',
    [string]$Password = '',
    [switch]$ConfirmLegacyH2
)

$ErrorActionPreference = 'Stop'
$taskProject = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))

function Get-AbsolutePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (-not [System.IO.Path]::IsPathRooted($Path)) {
        throw "$Label must be an absolute path: $Path"
    }
    return [System.IO.Path]::GetFullPath($Path)
}

function Resolve-RequiredFile {
    param(
        [string]$ExplicitPath,
        [string[]]$Candidates,
        [string]$Label
    )

    if ($ExplicitPath) {
        $taskExplicit = Get-AbsolutePath -Path $ExplicitPath -Label $Label
        if (-not (Test-Path -LiteralPath $taskExplicit -PathType Leaf)) {
            throw "$Label was not found: $taskExplicit"
        }
        return (Get-Item -LiteralPath $taskExplicit).FullName
    }

    foreach ($taskCandidate in $Candidates) {
        if ($taskCandidate -and (Test-Path -LiteralPath $taskCandidate -PathType Leaf)) {
            $taskResolved = (Get-Item -LiteralPath $taskCandidate).FullName
            return (Get-AbsolutePath -Path $taskResolved -Label $Label)
        }
    }
    throw "$Label was not found. Pass an absolute path explicitly."
}

function Get-JavaCandidates {
    param([ValidateSet('source', 'target')][string]$Role)

    $taskCandidates = New-Object System.Collections.Generic.List[string]
    $taskHomeVariables = if ($Role -eq 'source') {
        @($env:BACKUP_MANAGER_H2_SOURCE_JAVA_HOME, $env:JAVA_HOME)
    } else {
        @($env:BACKUP_MANAGER_JAVA_HOME, $env:JAVA_HOME)
    }
    foreach ($taskHome in $taskHomeVariables) {
        if ($taskHome) {
            $taskCandidates.Add((Join-Path $taskHome 'bin\java.exe'))
        }
    }

    if ($Role -eq 'source') {
        $taskCandidates.Add((Join-Path (Split-Path -Parent $taskProject) 'jdk-21\bin\java.exe'))
        $taskCandidates.Add((Join-Path $taskProject 'tools\jdk-21\bin\java.exe'))
    }

    $taskJavaRoots = @(
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Java')
    )
    foreach ($taskRoot in $taskJavaRoots) {
        if (Test-Path -LiteralPath $taskRoot -PathType Container) {
            Get-ChildItem -LiteralPath $taskRoot -Directory |
                Sort-Object Name -Descending |
                ForEach-Object { $taskCandidates.Add((Join-Path $_.FullName 'bin\java.exe')) }
        }
    }

    $taskJavaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($taskJavaCommand) {
        $taskCandidates.Add($taskJavaCommand.Source)
    }
    return $taskCandidates.ToArray()
}

function Get-JavaMajorVersion {
    param([string]$JavaExecutable)

    $taskVersionText = (& $JavaExecutable -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to run Java: $JavaExecutable"
    }
    $taskVersionMatch = [regex]::Match($taskVersionText, 'version\s+"(?<version>[^"]+)"')
    if (-not $taskVersionMatch.Success) {
        throw "Unable to identify the Java version from: $JavaExecutable"
    }
    $taskVersion = $taskVersionMatch.Groups['version'].Value
    if ($taskVersion.StartsWith('1.')) {
        return [int]($taskVersion.Split('.')[1])
    }
    return [int]([regex]::Match($taskVersion, '^\d+').Value)
}

function Resolve-Java {
    param(
        [string]$ExplicitPath,
        [ValidateSet('source', 'target')][string]$Role,
        [int]$MinimumMajor
    )

    if ($ExplicitPath) {
        $taskPath = Get-AbsolutePath -Path $ExplicitPath -Label "$Role Java"
        if (Test-Path -LiteralPath $taskPath -PathType Container) {
            $taskPath = Join-Path $taskPath 'bin\java.exe'
        }
        if (-not (Test-Path -LiteralPath $taskPath -PathType Leaf)) {
            throw "$Role Java was not found: $taskPath"
        }
        $taskMajor = Get-JavaMajorVersion -JavaExecutable $taskPath
        if ($taskMajor -lt $MinimumMajor) {
            throw "$Role Java must be version $MinimumMajor or newer; found Java $taskMajor at $taskPath"
        }
        return (Get-Item -LiteralPath $taskPath).FullName
    }

    foreach ($taskCandidate in (Get-JavaCandidates -Role $Role)) {
        if (-not (Test-Path -LiteralPath $taskCandidate -PathType Leaf)) {
            continue
        }
        try {
            $taskMajor = Get-JavaMajorVersion -JavaExecutable $taskCandidate
            if ($taskMajor -ge $MinimumMajor) {
                return (Get-Item -LiteralPath $taskCandidate).FullName
            }
        } catch {
            continue
        }
    }
    throw "$Role Java $MinimumMajor or newer was not found. Pass an absolute path explicitly."
}

function Assert-H2JarVersion {
    param(
        [string]$JarPath,
        [string]$ExpectedVersion,
        [string]$Label
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskArchive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $taskManifestEntry = $taskArchive.GetEntry('META-INF/MANIFEST.MF')
        if (-not $taskManifestEntry) {
            throw "$Label does not contain META-INF/MANIFEST.MF: $JarPath"
        }
        $taskReader = New-Object System.IO.StreamReader($taskManifestEntry.Open())
        try {
            $taskManifest = $taskReader.ReadToEnd()
        } finally {
            $taskReader.Dispose()
        }
    } finally {
        $taskArchive.Dispose()
    }
    $taskVersionMatch = [regex]::Match($taskManifest, '(?m)^Implementation-Version:\s*(?<version>[^\r\n]+)')
    if (-not $taskVersionMatch.Success -or $taskVersionMatch.Groups['version'].Value.Trim() -ne $ExpectedVersion) {
        throw "$Label must be H2 ${ExpectedVersion}: $JarPath"
    }
}

function Assert-BackendStopped {
    param(
        [string]$ProjectPath,
        [string]$DatabaseFile
    )

    try {
        $taskJavaProcesses = Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'"
    } catch {
        throw "Cannot inspect Java processes. The migration stops because backend occupancy cannot be ruled out: $($_.Exception.Message)"
    }
    $taskBackendProcesses = @($taskJavaProcesses | Where-Object {
        $taskCommandLine = [string]$_.CommandLine
        $taskCommandLine -match 'com\.example\.backupmanager\.BackupManagerApplication' -or
            $taskCommandLine -match '(?i)-jar\s+[^\r\n]*backup-manager-[^\s]*\.jar' -or
            ($taskCommandLine -match '(?i)spring-boot:run' -and $taskCommandLine.IndexOf($ProjectPath, [System.StringComparison]::OrdinalIgnoreCase) -ge 0)
    })
    if ($taskBackendProcesses.Count -gt 0) {
        $taskProcessSummary = ($taskBackendProcesses | ForEach-Object { "PID=$($_.ProcessId) $($_.CommandLine)" }) -join [Environment]::NewLine
        throw "A backup-manager backend process is still running. Stop it before migration.$([Environment]::NewLine)$taskProcessSummary"
    }

    $taskStream = $null
    try {
        $taskStream = [System.IO.File]::Open($DatabaseFile, 'Open', 'ReadWrite', 'None')
    } catch {
        throw "The H2 database file is in use or cannot be locked exclusively: $DatabaseFile. Stop the backend and any H2 tools before migration."
    } finally {
        if ($taskStream) {
            $taskStream.Dispose()
        }
    }
}

function Invoke-CheckedJava {
    param(
        [string]$JavaExecutable,
        [string[]]$Arguments,
        [string]$FailureMessage
    )

    & $JavaExecutable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (exit code $LASTEXITCODE)"
    }
}

if (-not $ConfirmLegacyH2) {
    throw 'This one-time tool is only for a database last opened by H2 2.3.232. Rerun with -ConfirmLegacyH2 after confirming that source version.'
}

if (-not $DatabaseBasePath) {
    $DatabaseBasePath = Join-Path $taskProject 'data\backup_manager'
}
$taskDatabaseBase = Get-AbsolutePath -Path $DatabaseBasePath -Label 'DatabaseBasePath'
if ($taskDatabaseBase.EndsWith('.mv.db', [System.StringComparison]::OrdinalIgnoreCase)) {
    $taskDatabaseBase = $taskDatabaseBase.Substring(0, $taskDatabaseBase.Length - '.mv.db'.Length)
}
$taskDatabaseFile = "$taskDatabaseBase.mv.db"
if (-not (Test-Path -LiteralPath $taskDatabaseFile -PathType Leaf)) {
    throw "The source H2 database was not found: $taskDatabaseFile"
}
$taskDatabaseFile = (Get-Item -LiteralPath $taskDatabaseFile).FullName
$taskDatabaseBase = $taskDatabaseFile.Substring(0, $taskDatabaseFile.Length - '.mv.db'.Length)

$taskMavenRoots = @(
    $env:BACKUP_MANAGER_MAVEN_REPO,
    (Join-Path (Split-Path -Parent $taskProject) '.m2'),
    (Join-Path $env:USERPROFILE '.m2\repository')
) | Where-Object { $_ }
$taskSourceJarCandidates = @(
    (Join-Path $taskProject 'tools\h2\h2-2.3.232.jar')
) + @($taskMavenRoots | ForEach-Object { Join-Path $_ 'com\h2database\h2\2.3.232\h2-2.3.232.jar' })
$taskTargetJarCandidates = @(
    (Join-Path $taskProject 'tools\h2\h2-2.2.224.jar')
) + @($taskMavenRoots | ForEach-Object { Join-Path $_ 'com\h2database\h2\2.2.224\h2-2.2.224.jar' })

$taskSourceJar = Resolve-RequiredFile -ExplicitPath $SourceH2Jar -Candidates $taskSourceJarCandidates -Label 'SourceH2Jar'
$taskTargetJar = Resolve-RequiredFile -ExplicitPath $TargetH2Jar -Candidates $taskTargetJarCandidates -Label 'TargetH2Jar'
$taskSourceJava = Resolve-Java -ExplicitPath $SourceJavaPath -Role source -MinimumMajor 11
$taskTargetJava = Resolve-Java -ExplicitPath $TargetJavaPath -Role target -MinimumMajor 8
Assert-H2JarVersion -JarPath $taskSourceJar -ExpectedVersion '2.3.232' -Label 'SourceH2Jar'
Assert-H2JarVersion -JarPath $taskTargetJar -ExpectedVersion '2.2.224' -Label 'TargetH2Jar'

$taskBusyArtifacts = @("$taskDatabaseBase.lock.db", "$taskDatabaseBase.temp.db")
foreach ($taskBusyArtifact in $taskBusyArtifacts) {
    if (Test-Path -LiteralPath $taskBusyArtifact) {
        throw "A transient H2 file is present. Stop all H2 processes and confirm the database closed cleanly: $taskBusyArtifact"
    }
}
Assert-BackendStopped -ProjectPath $taskProject -DatabaseFile $taskDatabaseFile

$taskTimestamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
$taskBackupBase = "$taskDatabaseBase.before-h2-2.2.224.$taskTimestamp"
$taskMigrationId = [Guid]::NewGuid().ToString('N')
$taskTempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "backup-manager-h2-migration-$taskMigrationId"
$taskTempDirectory = [System.IO.Path]::GetFullPath($taskTempDirectory)
$taskTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
if (-not $taskTempDirectory.StartsWith("$taskTempRoot$([System.IO.Path]::DirectorySeparatorChar)", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "The migration working directory is outside the system temporary directory: $taskTempDirectory"
}
$taskExportFile = Join-Path $taskTempDirectory 'full-export.sql'
$taskHeldFiles = New-Object System.Collections.Generic.List[object]
$taskCommitted = $false
$taskBackupFiles = New-Object System.Collections.Generic.List[string]
$taskOriginalArtifacts = @()

[void][System.IO.Directory]::CreateDirectory($taskTempDirectory)
try {
    $taskJdbcBase = $taskDatabaseBase.Replace('\', '/')
    $taskSourceUrl = "jdbc:h2:file:$taskJdbcBase;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE"
    Write-Host 'Exporting the complete H2 2.3.232 database before creating any target database...'
    Invoke-CheckedJava -JavaExecutable $taskSourceJava -Arguments @(
        '-cp', $taskSourceJar,
        'org.h2.tools.Script',
        '-url', $taskSourceUrl,
        '-user', $User,
        '-password', $Password,
        '-script', $taskExportFile,
        '-options', 'DROP'
    ) -FailureMessage 'H2 2.3.232 export failed; the original database was not changed'
    if (-not (Test-Path -LiteralPath $taskExportFile -PathType Leaf) -or (Get-Item -LiteralPath $taskExportFile).Length -eq 0) {
        throw 'H2 export did not produce a non-empty SQL file; the original database was not changed.'
    }

    $taskOriginalArtifacts = @($taskDatabaseFile, "$taskDatabaseBase.trace.db") | Where-Object {
        Test-Path -LiteralPath $_ -PathType Leaf
    }
    foreach ($taskOriginalArtifact in $taskOriginalArtifacts) {
        $taskExtension = if ($taskOriginalArtifact.EndsWith('.mv.db', [System.StringComparison]::OrdinalIgnoreCase)) { '.mv.db' } else { '.trace.db' }
        $taskBackupFile = "$taskBackupBase$taskExtension"
        if (Test-Path -LiteralPath $taskBackupFile) {
            throw "Refusing to overwrite an existing migration backup: $taskBackupFile"
        }
        [System.IO.File]::Copy($taskOriginalArtifact, $taskBackupFile, $false)
        $taskOriginalHash = (Get-FileHash -LiteralPath $taskOriginalArtifact -Algorithm SHA256).Hash
        $taskBackupHash = (Get-FileHash -LiteralPath $taskBackupFile -Algorithm SHA256).Hash
        if ($taskOriginalHash -ne $taskBackupHash) {
            throw "Backup verification failed: $taskBackupFile"
        }
        $taskBackupFiles.Add($taskBackupFile)
    }

    foreach ($taskOriginalArtifact in $taskOriginalArtifacts) {
        $taskHoldFile = "$taskOriginalArtifact.migration-hold-$taskMigrationId"
        if (Test-Path -LiteralPath $taskHoldFile) {
            throw "Refusing to overwrite a migration hold file: $taskHoldFile"
        }
        [System.IO.File]::Move($taskOriginalArtifact, $taskHoldFile)
        $taskHeldFiles.Add([pscustomobject]@{ Original = $taskOriginalArtifact; Hold = $taskHoldFile })
    }

    $taskTargetUrl = "jdbc:h2:file:$taskJdbcBase;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
    Write-Host 'Creating a new H2 2.2.224 database from the completed export...'
    Invoke-CheckedJava -JavaExecutable $taskTargetJava -Arguments @(
        '-cp', $taskTargetJar,
        'org.h2.tools.RunScript',
        '-url', $taskTargetUrl,
        '-user', $User,
        '-password', $Password,
        '-script', $taskExportFile
    ) -FailureMessage 'H2 2.2.224 import failed'
    if (-not (Test-Path -LiteralPath $taskDatabaseFile -PathType Leaf) -or (Get-Item -LiteralPath $taskDatabaseFile).Length -eq 0) {
        throw 'H2 2.2.224 import did not create a non-empty database file.'
    }
    $taskSessionCleanupOutput = (& $taskTargetJava @(
        '-cp', $taskTargetJar,
        'org.h2.tools.Shell',
        '-url', "$taskTargetUrl;IFEXISTS=TRUE",
        '-user', $User,
        '-password', $Password,
        '-sql', "SELECT H2VERSION(); SET AUTOCOMMIT FALSE; DELETE FROM SPRING_SESSION_ATTRIBUTES; DELETE FROM SPRING_SESSION; COMMIT; SELECT CASE WHEN (SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES) = 0 AND (SELECT COUNT(*) FROM SPRING_SESSION) = 0 THEN 'SESSION_CLEANUP_OK' ELSE 'SESSION_CLEANUP_FAILED' END AS SESSION_CLEANUP_RESULT"
    ) 2>&1 | Out-String)
    $taskSessionCleanupExitCode = $LASTEXITCODE
    Write-Host $taskSessionCleanupOutput.TrimEnd()
    if ($taskSessionCleanupExitCode -ne 0) {
        throw "The migrated H2 2.2.224 database could not be reopened or its legacy sessions could not be cleared (exit code $taskSessionCleanupExitCode)"
    }
    if ($taskSessionCleanupOutput -notmatch 'SESSION_CLEANUP_OK') {
        throw 'The migrated database still contains legacy Spring Session rows.'
    }

    $taskCommitted = $true
    foreach ($taskHeldFile in $taskHeldFiles) {
        try {
            [System.IO.File]::Delete($taskHeldFile.Hold)
        } catch {
            Write-Warning "The migration succeeded, but a temporary hold file could not be removed: $($taskHeldFile.Hold)"
        }
    }
    Write-Host 'H2 migration completed successfully.'
    Write-Host "Migrated database: $taskDatabaseFile"
    Write-Host "Original backup: $($taskBackupFiles -join ', ')"
} catch {
    $taskFailure = $_
    if (-not $taskCommitted -and $taskHeldFiles.Count -gt 0) {
        $taskRollbackErrors = New-Object System.Collections.Generic.List[string]
        $taskOriginalArtifactLookup = @{}
        foreach ($taskOriginalArtifact in $taskOriginalArtifacts) {
            $taskOriginalArtifactLookup[$taskOriginalArtifact] = $true
        }
        $taskHeldOriginalLookup = @{}
        foreach ($taskHeldFile in $taskHeldFiles) {
            $taskHeldOriginalLookup[$taskHeldFile.Original] = $true
        }
        foreach ($taskGeneratedArtifact in @(
            $taskDatabaseFile,
            "$taskDatabaseBase.trace.db",
            "$taskDatabaseBase.lock.db",
            "$taskDatabaseBase.temp.db"
        )) {
            try {
                $taskWasMovedAside = $taskHeldOriginalLookup.ContainsKey($taskGeneratedArtifact)
                $taskDidNotExistBefore = -not $taskOriginalArtifactLookup.ContainsKey($taskGeneratedArtifact)
                if (($taskWasMovedAside -or $taskDidNotExistBefore) -and (Test-Path -LiteralPath $taskGeneratedArtifact)) {
                    [System.IO.File]::Delete($taskGeneratedArtifact)
                }
            } catch {
                $taskRollbackErrors.Add("Could not remove ${taskGeneratedArtifact}: $($_.Exception.Message)")
            }
        }
        for ($taskIndex = $taskHeldFiles.Count - 1; $taskIndex -ge 0; $taskIndex--) {
            $taskHeldFile = $taskHeldFiles[$taskIndex]
            try {
                if (-not (Test-Path -LiteralPath $taskHeldFile.Original)) {
                    [System.IO.File]::Move($taskHeldFile.Hold, $taskHeldFile.Original)
                }
            } catch {
                $taskRollbackErrors.Add("Could not restore $($taskHeldFile.Original): $($_.Exception.Message)")
            }
        }
        if ($taskRollbackErrors.Count -gt 0) {
            throw "Migration failed: $($taskFailure.Exception.Message)$([Environment]::NewLine)Automatic rollback was incomplete. Keep the timestamped backup and resolve these errors before starting the backend:$([Environment]::NewLine)$($taskRollbackErrors -join [Environment]::NewLine)"
        }
        throw "Migration failed and the original database was restored automatically: $($taskFailure.Exception.Message)"
    }
    throw
} finally {
    if (Test-Path -LiteralPath $taskTempDirectory) {
        try {
            [System.IO.Directory]::Delete($taskTempDirectory, $true)
        } catch {
            Write-Warning "The temporary SQL export could not be removed: $taskTempDirectory"
        }
    }
}
