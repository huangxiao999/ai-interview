# Trusted local maintenance only. The backend generates a random password and displays it once.
param(
    [Parameter(Mandatory = $true)]
    [guid] $AccountId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Use-LocalTools.ps1')

$backend = Join-Path $projectRoot 'backend'
$jar = Join-Path $backend 'target/interview-coach-backend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw 'Build the backend JAR first. No account was reset.'
}

$originalDirectory = Get-Location
try {
    Set-Location $backend
    & java -jar $jar '--spring.profiles.active=local,reset' '--spring.main.web-application-type=none' "--app.reset.account-id=$AccountId"
    if ($LASTEXITCODE -ne 0) { throw 'Trial account password reset failed or its result is unknown; inspect non-secret startup errors and account state.' }
}
finally {
    Set-Location $originalDirectory
}
