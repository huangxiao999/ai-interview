# Runs DT-04 HTTP and Session checks against a fresh PostgreSQL database. No database is dropped.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Use-LocalTools.ps1')

$originalDirectory = Get-Location
$savedEnvironment = @{}
foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'DT04_TEST_DATABASE')) {
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
        throw 'DT-04 verification requires the documented local PostgreSQL URL without query parameters.'
    }
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }

    $testDatabase = 'dt04_verify_' + [guid]::NewGuid().ToString('N').Substring(0, 16)
    docker compose exec -T postgres sh -c 'exec createdb -U "$POSTGRES_USER" --maintenance-db="$POSTGRES_DB" "$1"' sh $testDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh isolated verification database.' }

    $env:DB_URL = $localValues['DB_URL'] -replace '/[^/]+$', "/$testDatabase"
    $env:DB_USERNAME = $localValues['DB_USERNAME']
    $env:DB_PASSWORD = $localValues['DB_PASSWORD']
    $env:DT04_TEST_DATABASE = $testDatabase
    $manifest = @{ database = $testDatabase; createdAt = [DateTimeOffset]::Now.ToString('o'); status = 'running' }
    $logPath = Join-Path $projectRoot ".cache/$testDatabase.log"
    $manifestPath = Join-Path $projectRoot ".cache/$testDatabase.json"
    $manifest['log'] = ".cache/$testDatabase.log"
    $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Write-Output "Isolated DT-04 verification database: $testDatabase (retained for inspection)."

    Set-Location (Join-Path $projectRoot 'backend')
    ./mvnw.cmd '-Dmaven.repo.local=../.cache/maven-repository' -B -ntp -Pdatabase-verification '-Dit.test=PasswordResetIT' verify *> $logPath
    $verificationExit = $LASTEXITCODE
    $manifest['status'] = if ($verificationExit -eq 0) { 'passed' } else { 'failed' }
    $manifest['exitCode'] = $verificationExit
    $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    $manifest | ConvertTo-Json | Set-Content -LiteralPath '../.cache/dt04-test-run.json' -Encoding UTF8
    Get-Content -LiteralPath $logPath -Tail 28
    if ($verificationExit -ne 0) { throw "DT-04 verification failed; see $logPath and backend/target/failsafe-reports." }
}
finally {
    Set-Location $originalDirectory
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}

