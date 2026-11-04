param([ValidateSet('Migration','Plan','Revision','History','Collection','Index','Materials','Trial')][string]$Suite='Plan', [ValidatePattern('^[A-Za-z0-9_*+]*$')][string]$Cases='')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Use-LocalTools.ps1')
$originalDirectory=Get-Location
$saved=@{}
foreach($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','P4_PLAN_TEST_DATABASE','P3_COLLECTION_TEST_DATABASE','P3_INDEX_TEST_DATABASE','P2_DT01_TEST_DATABASE')){
 $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
}
$appProcess=$null
if($Suite -eq 'Trial' -and (-not $Cases -or $Cases.Contains('+') -or $Cases.Contains('*'))){throw 'Trial requires exactly one scenario per fresh retained database; no authorization reset between cases'}
try{
 Set-Location $projectRoot
 $prefix=switch($Suite){'Collection'{'p3dt03_verify_'}'Index'{'p3dt02_verify_'}'Materials'{'p2dt01_verify_'}default{'p4dt01_verify_'}}
 $database=$prefix+[guid]::NewGuid().ToString('N').Substring(0,16)
 $volume=$database+'_data';$dbUser='plan_verifier';$dbPassword=[guid]::NewGuid().ToString('N')
 $image=(docker image inspect pgvector/pgvector:pg16 --format '{{.Id}}'|Out-String).Trim()
 if($LASTEXITCODE -ne 0){throw 'Existing PG16 image required; never pull or install'}
 $container=(docker run -d --name $database --label interview-coach.verification=p4-dt-01 -e "POSTGRES_USER=$dbUser" -e "POSTGRES_PASSWORD=$dbPassword" -e "POSTGRES_DB=$database" -p '127.0.0.1::5432' -v "${volume}:/var/lib/postgresql/data" pgvector/pgvector:pg16|Out-String).Trim()
 if($LASTEXITCODE -ne 0){throw 'Isolated container start failed; existing resources unchanged'}
 $mapping=(docker port $database 5432/tcp|Out-String).Trim();if($mapping -notmatch '127\.0\.0\.1:(\d+)'){throw 'Loopback mapping required'};$dbPort=$Matches[1]
 $ready=$false
 for($i=0;$i -lt 60;$i++){docker exec $database pg_isready -h 127.0.0.1 -U $dbUser -d $database *> $null;if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep -Milliseconds 500}
 if(-not $ready){throw 'Isolated PG16 not ready; retained'}
 docker exec $database psql -U $dbUser -d $database -v ON_ERROR_STOP=1 -c "CREATE EXTENSION vector; COMMENT ON DATABASE $database IS 'p4-plan-isolation-v1';" *> ".cache/$database-init.log"
 if($LASTEXITCODE -ne 0){throw 'Isolated extension/marker failed'}
 $env:DB_URL="jdbc:postgresql://127.0.0.1:${dbPort}/$database";$env:DB_USERNAME=$dbUser;$env:DB_PASSWORD=$dbPassword;$env:P4_PLAN_TEST_DATABASE=$database
 $sources=@(git -c core.quotepath=false ls-files --cached --others --exclude-standard|Where-Object{$_ -match '^(backend/|frontend/|scripts/)' -and $_ -notmatch '/target/|node_modules/'})
 $hashes=@{};foreach($source in $sources){if(Test-Path -LiteralPath $source -PathType Leaf){$hashes[$source]=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash}}
 $sourceArchive=Join-Path $projectRoot ".cache/$database-sources"
 foreach($source in $hashes.Keys){$destination=Join-Path $sourceArchive $source;New-Item -ItemType Directory -Path (Split-Path $destination) -Force|Out-Null;Copy-Item -LiteralPath (Join-Path $projectRoot $source) -Destination $destination}
 $manifest=@{suite=$Suite;head=(git rev-parse HEAD);createdAt=[DateTimeOffset]::Now.ToString('o');database=$database;container=$container;volume=$volume;image=$image;port=$dbPort;sourceSha256=$hashes;status='running'}
 $manifest['sourceArchive']=$sourceArchive;$manifest['caseFilter']=$Cases
 $manifestPath=Join-Path $projectRoot ".cache/$database.json";$logPath=Join-Path $projectRoot ".cache/$database.log"
 $manifest|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $manifestPath -Encoding utf8
 Set-Location backend
 # 保留IDE/既有Maven增量状态，强制本轮javac按受绑定源码重新编译，避免仅依赖时间戳。
 $compilerState=Join-Path $projectRoot 'backend/target/maven-status'
 $savedCompilerState=Join-Path $projectRoot ".cache/$database-compiler-status"
 if(Test-Path -LiteralPath $compilerState){Move-Item -LiteralPath $compilerState -Destination $savedCompilerState}
 if($Suite -eq 'Migration'){
  $manifest['command']="./mvnw.cmd -Dmaven.repo.local=../.cache/maven-repository -B -ntp -Dmaven.test.skip=true package; isolated actual JAR startup"
  ./mvnw.cmd '-Dmaven.repo.local=../.cache/maven-repository' -B -ntp '-Dmaven.test.skip=true' package *> $logPath
  if($LASTEXITCODE -ne 0){throw "Package failed: $logPath"}
  $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$listener.Start();$appPort=$listener.LocalEndpoint.Port;$listener.Stop()
  $stdout=Join-Path $projectRoot ".cache/$database-app.log";$stderr=Join-Path $projectRoot ".cache/$database-app.err"
  $appProcess=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -WindowStyle Hidden -PassThru -WorkingDirectory (Get-Location) -RedirectStandardOutput $stdout -RedirectStandardError $stderr -ArgumentList @('-Djdk.httpclient.redirects.retrylimit=1','-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.enableAllMethodRetry=false','-jar','target/interview-coach-backend-0.0.1-SNAPSHOT.jar','--spring.profiles.active=local','--spring.config.import=',"--server.port=$appPort",'--server.address=127.0.0.1','--BAILIAN_EMBEDDING_ENABLED=false')
  $healthy=$false
  for($i=0;$i -lt 80;$i++){if($appProcess.HasExited){break};try{$response=Invoke-RestMethod -Uri "http://127.0.0.1:$appPort/actuator/health" -TimeoutSec 2;if($response.status -eq 'UP'){$healthy=$true;break}}catch{};Start-Sleep -Milliseconds 500}
  $manifest['healthy']=$healthy;$manifest['jarSha256']=(Get-FileHash 'target/interview-coach-backend-0.0.1-SNAPSHOT.jar').Hash
  docker exec $database psql -U $dbUser -d $database -v ON_ERROR_STOP=1 -c "SELECT version,success FROM app.flyway_schema_history ORDER BY installed_rank; SELECT count(*) FROM app.plan_workspace;" *> (Join-Path $projectRoot ".cache/$database-migration.log")
  if(-not $healthy -or $LASTEXITCODE -ne 0){throw "Actual JAR startup/migration failed: $stdout"}
 }else{
  $test=switch($Suite){'Trial'{'ControlledPlanTrialIT'}'Plan'{'PlanGenerationIT'}'Revision'{'PlanRevisionIT'}'History'{'PlanHistoryIT'}'Collection'{$env:P3_COLLECTION_TEST_DATABASE=$database;'KnowledgeCollectionIT'}'Index'{$env:P3_INDEX_TEST_DATABASE=$database;'KnowledgeIndexIT'}'Materials'{$env:P2_DT01_TEST_DATABASE=$database;'ResumeMaterialIT'}}
  $start=[DateTime]::UtcNow
  $selection=if($Cases){"${test}#$Cases"}else{$test}
  if($Suite -eq 'Materials' -and -not $Cases){$selection='ResumeMaterialIT,CleanupRepositoryIT'}
  $manifest['command']="./mvnw.cmd -Dmaven.repo.local=../.cache/maven-repository -B -ntp -Pdatabase-verification -Dtest=PlanPolicyTest -Dit.test=$selection verify"
  ./mvnw.cmd '-Dmaven.repo.local=../.cache/maven-repository' -B -ntp -Pdatabase-verification '-Dtest=PlanPolicyTest' "-Dit.test=$selection" verify *> $logPath
  $exitCode=$LASTEXITCODE;$report=Join-Path $projectRoot "backend/target/failsafe-reports/TEST-dev.interviewcoach.identity.$test.xml"
  $reports=Join-Path $projectRoot ".cache/$database-reports";New-Item -ItemType Directory -Path $reports -Force|Out-Null
  foreach($folder in @('surefire-reports','failsafe-reports')){$destination=Join-Path $reports $folder;New-Item -ItemType Directory -Path $destination -Force|Out-Null;Get-ChildItem -LiteralPath (Join-Path $projectRoot "backend/target/$folder") -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue|Where-Object{$_.LastWriteTimeUtc -ge $start}|Copy-Item -Destination $destination}
  $manifest['reports']=$reports
  if(-not(Test-Path -LiteralPath $report) -or (Get-Item $report).LastWriteTimeUtc -lt $start){Get-Content -LiteralPath $logPath -Tail 15;throw "No fresh integration report; inspect compile/startup failure: $logPath"}
  [xml]$xml=Get-Content -LiteralPath $report -Raw;$manifest['tests']=[int]$xml.testsuite.tests
  if($exitCode -ne 0 -or [int]$xml.testsuite.skipped -ne 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0){throw "Verification failed: $logPath"}
  if($Suite -eq 'Materials' -and -not $Cases){
   $cleanupReport=Join-Path $projectRoot 'backend/target/failsafe-reports/TEST-dev.interviewcoach.material.CleanupRepositoryIT.xml'
   if(-not(Test-Path -LiteralPath $cleanupReport) -or (Get-Item $cleanupReport).LastWriteTimeUtc -lt $start){throw 'No fresh cleanup regression report'}
   [xml]$cleanupXml=Get-Content -LiteralPath $cleanupReport -Raw
   if([int]$cleanupXml.testsuite.skipped -ne 0 -or [int]$cleanupXml.testsuite.failures -ne 0 -or [int]$cleanupXml.testsuite.errors -ne 0){throw 'Cleanup regression failed'}
   $manifest['cleanupTests']=[int]$cleanupXml.testsuite.tests
  }
 }
 foreach($source in $hashes.Keys){if((Get-FileHash -LiteralPath (Join-Path $projectRoot $source)).Hash -ne $hashes[$source]){throw "Source changed during verification: $source"}}
 $manifest['status']='passed';Write-Output "Isolated $Suite passed: $database"
}catch{
 if(Get-Variable manifest -ErrorAction SilentlyContinue){$manifest['status']='failed';$manifest['failure']=$_.Exception.Message}
 throw
}finally{
 if($appProcess -and -not $appProcess.HasExited){Stop-Process -Id $appProcess.Id; $appProcess.WaitForExit()}
 if((Get-Variable manifestPath -ErrorAction SilentlyContinue) -and (Get-Variable manifest -ErrorAction SilentlyContinue)){$manifest['completedAt']=[DateTimeOffset]::Now.ToString('o');$manifest|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $manifestPath -Encoding utf8}
 foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
 Set-Location $originalDirectory
 # 本轮容器、卷、数据库、日志和报告全部保留；绝不down -v或清旧证据。
}
