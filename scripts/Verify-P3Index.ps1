Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Use-LocalTools.ps1')
$originalDirectory = Get-Location
$savedEnvironment = @{}
foreach ($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','P3_INDEX_TEST_DATABASE')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
}
try {
    Set-Location $projectRoot
    $localValues = @{}
    foreach ($line in Get-Content -LiteralPath '.env' -Encoding UTF8) {
        if ($line -match '^([A-Z_]+)=(.*)$') { $localValues[$Matches[1]]=$Matches[2] }
    }
    foreach ($required in @('DB_URL','DB_USERNAME','DB_PASSWORD')) {
        if (-not $localValues.ContainsKey($required) -or [string]::IsNullOrEmpty($localValues[$required])) { throw 'Local database configuration missing; no secret displayed' }
    }
    if ($localValues['DB_URL'] -notmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost):[0-9]+/[^?]+$') { throw 'Only the documented local PostgreSQL connection is supported' }
    $testDatabase = 'p3dt02_verify_' + [guid]::NewGuid().ToString('N').Substring(0,16)
    docker compose exec -T postgres sh -c 'exec createdb -U "$POSTGRES_USER" --maintenance-db="$POSTGRES_DB" "$1"' sh $testDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Could not create fresh isolated PG database' }
    $env:DB_URL = $localValues['DB_URL'] -replace '/[^/]+$', "/$testDatabase"
    $env:DB_USERNAME=$localValues['DB_USERNAME']; $env:DB_PASSWORD=$localValues['DB_PASSWORD']; $env:P3_INDEX_TEST_DATABASE=$testDatabase
    $manifestPath=Join-Path $projectRoot ".cache/$testDatabase.json"
    $logPath=Join-Path $projectRoot ".cache/$testDatabase.log"
    $sources=@(Get-ChildItem 'backend/src/main/java/dev/interviewcoach/knowledge' -Filter '*.java' | ForEach-Object { [IO.Path]::GetRelativePath($projectRoot,$_.FullName) })
    $sources+=@('backend/src/main/resources/db/migration/V7__create_reviewed_knowledge.sql','backend/src/test/java/dev/interviewcoach/identity/KnowledgeIndexIT.java','scripts/Verify-P3Index.ps1','scripts/Verify-P3IndexBrowser.mjs','backend/src/main/java/dev/interviewcoach/identity/LocalSecurityConfiguration.java','frontend/src/api/knowledge.ts','frontend/src/api/knowledgeIndex.ts','frontend/src/stores/knowledgeIndex.ts','frontend/src/views/KnowledgeIndexPage.vue','backend/src/main/resources/db/migration/V8__create_knowledge_index_builds.sql','frontend/src/stores/knowledge.ts','frontend/src/views/KnowledgePage.vue','frontend/src/router/index.ts','frontend/src/views/WorkspacePage.vue')
    $sources+='backend/src/test/java/dev/interviewcoach/identity/KnowledgeMaintenanceIT.java'
    $hashes=@{}; foreach ($source in $sources) { $hashes[$source]=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash }
    $manifest=@{ database=$testDatabase; createdAt=[DateTimeOffset]::Now.ToString('o'); status='running'; sourceSha256=$hashes; log=$logPath }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Write-Output "New isolated PG16 verification database: $testDatabase (retained)"
    # 编辑器可能先生成class并使Maven按时间戳误判为最新。保留旧编译状态，强制本轮javac重新编译。
    $compilerState=[IO.Path]::GetFullPath((Join-Path $projectRoot 'backend/target/maven-status'))
    $savedCompilerState=[IO.Path]::GetFullPath((Join-Path $projectRoot ".cache/$testDatabase-compiler-status"))
    foreach ($target in @($compilerState,$savedCompilerState)) {
        if (-not $target.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Compiler state path escaped workspace' }
    }
    if (Test-Path -LiteralPath $compilerState) { Move-Item -LiteralPath $compilerState -Destination $savedCompilerState }
    Set-Location backend
    $started=[DateTime]::UtcNow
    ./mvnw.cmd '-Dmaven.repo.local=../.cache/maven-repository' -B -ntp -Pdatabase-verification '-Dit.test=KnowledgeIndexIT' verify *> $logPath
    $result=$LASTEXITCODE
    $report=Join-Path $projectRoot 'backend/target/failsafe-reports/TEST-dev.interviewcoach.identity.KnowledgeIndexIT.xml'
    $reports=Join-Path $projectRoot ".cache/$testDatabase-reports"
    New-Item -ItemType Directory -Path $reports -Force | Out-Null
    foreach ($folder in @('surefire-reports','failsafe-reports')) {
        $destination=Join-Path $reports $folder
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $projectRoot "backend/target/$folder") -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTimeUtc -ge $started } | Copy-Item -Destination $destination
    }
    if (-not (Test-Path -LiteralPath $report) -or (Get-Item -LiteralPath $report).LastWriteTimeUtc -lt $started) { $result=1 }
    else { $xmlReport=[xml](Get-Content -LiteralPath $report -Raw); if ([int]$xmlReport.testsuite.skipped -ne 0 -or [int]$xmlReport.testsuite.tests -lt 15) { $result=1 } }
    foreach ($source in $sources) {
        if ((Get-FileHash -LiteralPath (Join-Path $projectRoot $source) -Algorithm SHA256).Hash -ne $hashes[$source]) { $result=1; $manifest['sourceChangedDuringRun']=$true }
    }
    $manifest['status']=if($result -eq 0){'passed'}else{'failed'}; $manifest['exitCode']=$result
    $manifest['reports']=$reports
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Get-Content -LiteralPath $logPath -Tail 25
    if ($result -ne 0) { throw "Knowledge verification failed; see $logPath" }
} finally {
    Set-Location $originalDirectory
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process') }
}

