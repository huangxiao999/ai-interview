# P2-DT-01/02/03: isolated real-PG16 version/file/HTTP/transaction/browser checks.
# Retain the original database prefix for existing isolation guards and historical evidence.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Use-LocalTools.ps1')

$originalDirectory = Get-Location
$savedEnvironment = @{}
foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'P2_DT01_TEST_DATABASE')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    Set-Location $projectRoot
    $localValues = @{}
    foreach ($line in Get-Content -LiteralPath '.env' -Encoding UTF8) {
        if ($line -match '^([A-Z_]+)=(.*)$') { $localValues[$Matches[1]] = $Matches[2] }
    }
    foreach ($required in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD')) {
        if (-not $localValues.ContainsKey($required) -or [string]::IsNullOrEmpty($localValues[$required])) {
            throw "Local configuration is missing $required; no secret was displayed."
        }
    }
    if ($localValues['DB_URL'] -notmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost):[0-9]+/[^?]+$') {
        throw 'P2-DT-01 requires the documented local PostgreSQL URL without query parameters.'
    }
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }

    $testDatabase = 'p2dt01_verify_' + [guid]::NewGuid().ToString('N').Substring(0, 16)
    docker compose exec -T postgres sh -c 'exec createdb -U "$POSTGRES_USER" --maintenance-db="$POSTGRES_DB" "$1"' sh $testDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh isolated verification database.' }

    $env:DB_URL = $localValues['DB_URL'] -replace '/[^/]+$', "/$testDatabase"
    $env:DB_USERNAME = $localValues['DB_USERNAME']
    $env:DB_PASSWORD = $localValues['DB_PASSWORD']
    $env:P2_DT01_TEST_DATABASE = $testDatabase
    $manifest = @{ database = $testDatabase; createdAt = [DateTimeOffset]::Now.ToString('o'); status = 'running' }
    $logPath = Join-Path $projectRoot ".cache/$testDatabase.log"
    $manifestPath = Join-Path $projectRoot ".cache/$testDatabase.json"
    $manifest['log'] = ".cache/$testDatabase.log"
    $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Write-Output "Isolated P2-DT-01 verification database: $testDatabase (retained for inspection)."

    Set-Location (Join-Path $projectRoot 'backend')
    $verificationStarted = [DateTime]::UtcNow
    ./mvnw.cmd '-Dmaven.repo.local=../.cache/maven-repository' -B -ntp -Pdatabase-verification `
        '-Dit.test=ResumeMaterialIT' verify *> $logPath
    $verificationExit = $LASTEXITCODE
    $report = Join-Path $projectRoot 'backend/target/failsafe-reports/TEST-dev.interviewcoach.identity.ResumeMaterialIT.xml'
    if ((Test-Path -LiteralPath $report) -and (Get-Item -LiteralPath $report).LastWriteTimeUtc -ge $verificationStarted) {
        $reportDirectory = Join-Path $projectRoot ".cache/$testDatabase-reports"
        New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
        Copy-Item -LiteralPath $report -Destination $reportDirectory
        $unitDirectory = Join-Path $reportDirectory 'unit'
        New-Item -ItemType Directory -Path $unitDirectory -Force | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'backend/target/surefire-reports') -Filter 'TEST-*.xml' |
            Where-Object { $_.LastWriteTimeUtc -ge $verificationStarted } |
            Copy-Item -Destination $unitDirectory
        $manifest['report'] = ".cache/$testDatabase-reports/TEST-dev.interviewcoach.identity.ResumeMaterialIT.xml"
    } elseif ($verificationExit -eq 0) {
        $verificationExit = 1
    }
    $manifest['migrationV5Sha256'] = (Get-FileHash -LiteralPath (Join-Path $projectRoot 'backend/src/main/resources/db/migration/V5__create_private_uploads.sql') -Algorithm SHA256).Hash
    $manifest['parserWorkerSha256'] = (Get-FileHash -LiteralPath (Join-Path $projectRoot 'backend/src/main/java/dev/interviewcoach/material/ParserWorker.java') -Algorithm SHA256).Hash
    $manifest['status'] = if ($verificationExit -eq 0) { 'passed' } else { 'failed' }
    $manifest['exitCode'] = $verificationExit
    $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    $manifest | ConvertTo-Json | Set-Content -LiteralPath '../.cache/p2-dt01-test-run.json' -Encoding UTF8
    Get-Content -LiteralPath $logPath -Tail 35
    if ($verificationExit -ne 0) { throw "P2-DT-01 verification failed; see $logPath and failsafe reports." }
}
finally {
    Set-Location $originalDirectory
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
