# Uses a fresh guarded PG16 database and actual private files; retains all evidence.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Verify-P2Resume.ps1') -IncludeCleanup
