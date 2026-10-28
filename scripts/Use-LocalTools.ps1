# Apply only to the current PowerShell process; no installation or global configuration changes.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path $PSScriptRoot -Parent
$javaExecutable = (Get-Command java -CommandType Application -ErrorAction Stop).Source
$currentJavaHome = Split-Path (Split-Path $javaExecutable -Parent) -Parent
if (-not (Test-Path -LiteralPath (Join-Path $currentJavaHome 'bin/javac.exe'))) {
    throw 'Point the current terminal PATH at a full JDK 21 bin directory before loading this script.'
}

$javaVersionText = (& $javaExecutable --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $javaVersionText -notmatch '^(?:openjdk|java)\s+21(?:[.\s])') {
    throw 'Use the existing JDK 21. See README; no JDK was installed or switched automatically.'
}
$env:JAVA_HOME = $currentJavaHome
$env:PATH = "$(Join-Path $currentJavaHome 'bin');$env:PATH"

$portableNode = Join-Path $projectRoot '.tools/node-v22.23.4-win-x64'
if (Test-Path -LiteralPath (Join-Path $portableNode 'node.exe')) {
    $env:PATH = "$portableNode;$env:PATH"
}

$nodeVersionText = (& node --version).Trim()
$nodeVersion = [version]$nodeVersionText.TrimStart('v')
if ($nodeVersion.Major -ne 22 -or $nodeVersion.Minor -lt 18) {
    throw 'Use Node.js 22.18+ (22.x). See .node-version and README; no global installation was changed.'
}

$cacheRoot = Join-Path $projectRoot '.cache'
$env:MAVEN_USER_HOME = Join-Path $cacheRoot 'maven-user-home'
$env:npm_config_cache = Join-Path $cacheRoot 'npm'
$temporaryDirectory = Join-Path $cacheRoot 'tmp'
New-Item -ItemType Directory -Path $env:MAVEN_USER_HOME, $env:npm_config_cache, $temporaryDirectory -Force | Out-Null

# Keep Windows Java NIO local socket names short, including when launched by a restricted executor.
$env:TEMP = $temporaryDirectory
$env:TMP = $temporaryDirectory

Write-Output "Current-shell development tools: $(($javaVersionText -split '\r?\n')[0]); Node $nodeVersionText; project-local caches and temporary directory."
