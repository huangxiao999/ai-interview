param(
    [ValidateRange(1024,65535)][int]$FrontendPort = 18080,
    [ValidateRange(1024,65535)][int]$BackendPort = 18081
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Use-LocalTools.ps1')
$originalDirectory = Get-Location
$savedEnvironment = @{}
foreach ($name in @('DESKTOP_FRONTEND_PORT','DESKTOP_BACKEND_PORT')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
}
try {
    Set-Location $projectRoot
    if ($FrontendPort -eq $BackendPort) { throw 'Choose two distinct ports' }
    docker info --format '{{.OSType}}' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop Linux containers first' }

    # 与compose.yaml使用同一受控.env，仅显式引用数据库变量；不注入整个env_file。
    $envFile = Join-Path $projectRoot '.env'
    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { throw 'Prepare the project root .env for the existing development database first' }
    $env:DESKTOP_FRONTEND_PORT=$FrontendPort.ToString()
    $env:DESKTOP_BACKEND_PORT=$BackendPort.ToString()

    # 仅允许已有、已启动并已完成当前迁移的原开发库；本脚本不授权迁移。
    $targetRaw = & docker compose --env-file $envFile -f compose.yaml config --format json
    if ($LASTEXITCODE -ne 0) { throw 'Development Compose configuration invalid' }
    $target = ($targetRaw | ConvertFrom-Json).services.postgres.environment
    $inspection = & docker inspect ai-interview-dev-postgres-1
    if ($LASTEXITCODE -ne 0) { throw 'Start the existing development PostgreSQL container first' }
    $databaseContainer = ($inspection | ConvertFrom-Json)[0]
    if (-not $databaseContainer.State.Running) { throw 'Start the existing development PostgreSQL container first' }
    foreach ($key in @('POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD')) {
        if ($databaseContainer.Config.Env -notcontains ($key+'='+$target.$key)) { throw 'Existing database configuration differs from compose.yaml/root .env; credentials are not displayed' }
    }
    $versions = & docker exec ai-interview-dev-postgres-1 psql -X -U $target.POSTGRES_USER -d $target.POSTGRES_DB -v ON_ERROR_STOP=1 -Atc "BEGIN READ ONLY; SELECT version FROM app.flyway_schema_history WHERE success=true AND version IS NOT NULL ORDER BY installed_rank; COMMIT;"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify existing database migration baseline; no application changes applied' }
    $applied=@($versions | Where-Object { $_ -match '^\d+$' })
    $required=@(Get-ChildItem (Join-Path $projectRoot 'backend/src/main/resources/db/migration') -Filter 'V*.sql' | ForEach-Object { if ($_.Name -match '^V(\d+)__') {[int]$Matches[1]} } | Sort-Object)
    if (($applied -join ',') -ne ($required -join ',')) { throw "Existing development database is V$($applied -join '/V'); current application needs V$($required -join '/V'). Stop before deployment; schema migration requires separate authorization." }
    $compose = @('compose','--env-file',$envFile,'-f','compose.desktop.yaml')
    & docker @compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop Compose configuration invalid; no credentials displayed' }

    $runId = 'docker-desktop-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6)
    $log = Join-Path $projectRoot ".cache/$runId.log"
    # 保留旧编译状态和证据，避免编辑器class时间戳使Maven误用旧字节码；不执行clean。
    $compilerState = [IO.Path]::GetFullPath((Join-Path $projectRoot 'backend/target/maven-status'))
    $savedCompilerState = [IO.Path]::GetFullPath((Join-Path $projectRoot ".cache/$runId-compiler-status"))
    foreach ($target in @($compilerState,$savedCompilerState)) {
        if (-not $target.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Compiler state path escaped workspace' }
    }
    if (Test-Path -LiteralPath $compilerState) { Move-Item -LiteralPath $compilerState -Destination $savedCompilerState }
    Write-Output 'Packaging backend with host JDK21; existing test evidence is retained.'
    Set-Location (Join-Path $projectRoot 'backend')
    ./mvnw.cmd '-Dmaven.repo.local=../.cache/maven-repository' -B -ntp '-DskipTests' package *> $log
    if ($LASTEXITCODE -ne 0) { throw "Backend packaging failed; see $log" }
    Set-Location (Join-Path $projectRoot 'frontend')
    npm.cmd run check *>> $log
    if ($LASTEXITCODE -ne 0) { throw "Frontend checks/build failed; see $log" }
    Set-Location $projectRoot
    Write-Output 'Building backend/frontend images and starting ai-interview-desktop.'
    & docker @compose build --pull=false *>> $log
    if ($LASTEXITCODE -ne 0) { throw "Docker build failed; see $log" }
    & docker @compose up -d --wait --wait-timeout 180 *>> $log
    if ($LASTEXITCODE -ne 0) { throw "Docker startup failed; resources retained; see $log" }
    $manifest = @{
        createdAt=[DateTimeOffset]::Now.ToString('o'); head=(git rev-parse HEAD); status='started'; log=$log
        project='ai-interview-desktop'; frontend="http://127.0.0.1:$FrontendPort"; backend="http://127.0.0.1:$BackendPort"; database='compose.yaml/interview_coach'
        jarSha256=(Get-FileHash -LiteralPath 'backend/target/interview-coach-backend-0.0.1-SNAPSHOT.jar' -Algorithm SHA256).Hash
        frontendEntrySha256=(Get-FileHash -LiteralPath 'frontend/dist/index.html' -Algorithm SHA256).Hash
    }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath ".cache/$runId.json" -Encoding UTF8
    & docker @compose ps
    Write-Output "Frontend: http://127.0.0.1:$FrontendPort / Backend health: http://127.0.0.1:$BackendPort/actuator/health"
    Write-Output "Build/start record: .cache/$runId.json (no model calls; root .env untouched)"
} finally {
    Set-Location $originalDirectory
    foreach ($name in $savedEnvironment.Keys) {
        if ($null -eq $savedEnvironment[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process') }
    }
}
