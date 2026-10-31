# P2-DT-03 extends the existing isolated PG / HTTP / real Chrome suite.
# Keep one fixture and one isolation guard rather than creating a second verification framework.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Verify-P2Resume.ps1')
