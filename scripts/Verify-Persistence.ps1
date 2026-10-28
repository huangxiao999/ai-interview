# Creates a fresh, explicitly isolated database in this project's PG16 service. No database is dropped.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Use-LocalTools.ps1')

$originalDirectory = Get-Location
$savedEnvironment = @{}
foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'DT01_TEST_DATABASE')) {
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
            throw "Local configuration is missing $required; no password was displayed."
        }
    }
    if ($localValues['DB_URL'] -notmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost):[0-9]+/[^?]+$') {
        throw 'The verification runner requires the documented local PostgreSQL URL without query parameters.'
    }
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }

    # These identifiers are generated locally, never interpolated from a user-supplied SQL fragment.
    $testDatabase = 'dt01_verify_' + [guid]::NewGuid().ToString('N').Substring(0, 16)
    docker compose exec -T postgres sh -c 'exec createdb -U "$POSTGRES_USER" --maintenance-db="$POSTGRES_DB" "$1"' sh $testDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh isolated verification database.' }
    'CREATE EXTENSION IF NOT EXISTS vector;' | docker compose exec -T postgres sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh $testDatabase | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare vector in the isolated database.' }

    $env:DB_URL = $localValues['DB_URL'] -replace '/[^/]+$', "/$testDatabase"
    $env:DB_USERNAME = $localValues['DB_USERNAME']
    $env:DB_PASSWORD = $localValues['DB_PASSWORD']
    $env:DT01_TEST_DATABASE = $testDatabase
    $manifest = @{ database = $testDatabase; createdAt = [DateTimeOffset]::Now.ToString('o'); status = 'running' }
    $logPath = Join-Path $projectRoot ".cache/$testDatabase.log"
    $manifestPath = Join-Path $projectRoot ".cache/$testDatabase.json"
    $manifest['log'] = ".cache/$testDatabase.log"
    $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    $manifest | ConvertTo-Json | Set-Content -LiteralPath '.cache/dt01-test-run.json' -Encoding UTF8
    Write-Output "Isolated verification database: $testDatabase (retained for inspection)."
    Set-Location (Join-Path $projectRoot 'backend')
    ./mvnw.cmd '-Dmaven.repo.local=../.cache/maven-repository' -B -ntp -Pdatabase-verification verify *> $logPath
    $verificationExit = $LASTEXITCODE
    $manifest['status'] = if ($verificationExit -eq 0) { 'passed' } else { 'failed' }
    $manifest['exitCode'] = $verificationExit
    $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    $manifest | ConvertTo-Json | Set-Content -LiteralPath '../.cache/dt01-test-run.json' -Encoding UTF8
    Get-Content -LiteralPath $logPath -Tail 24
    if ($verificationExit -ne 0) { throw "DT-01 verification failed; see $logPath and backend/target/failsafe-reports." }
}
finally {
    Set-Location $originalDirectory
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
