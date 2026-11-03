# Docker Desktop 前后端构建与启动记录

版本：v0.4｜日期：2026-11-03｜状态：用户单独授权后完成原interview_coach备份、隔离恢复/迁移演练、原库V3～V8追加迁移及Desktop连接切换，2账号及权限指纹不变，健康/HTTP/Chrome检查通过，见第8节。第2～7节保留此前部署及停止时点，不代表当前仍待授权。embedding关闭，无真实调用。

## 1. 访问与日常启动

在Docker Desktop的Containers中查看项目 **ai-interview-desktop**（frontend、backend）；数据库复用 **ai-interview-dev** 的postgres服务。两个项目共用ai-interview-dev_default网络，数据库无需映射容器内的15432端口，后端走5432。

| 入口 | 地址 |
| --- | --- |
| 前端/登录页 | http://127.0.0.1:18080/login |
| 前端首页 | http://127.0.0.1:18080/ |
| 后端健康 | http://127.0.0.1:18081/actuator/health |
| 同源代理健康 | http://127.0.0.1:18080/actuator/health |
| 当前数据库 | 本机15432端口；原库/用户interview_coach，容器内5432 |

全部宿主机端口仅绑定127.0.0.1。原开发库已按本轮明确授权升级V8，原数据卷及2账号保留；旧Desktop数据库卷保留但不再使用。

项目根目录先启动已有原开发库，再构建启动前后端：

```powershell
docker start ai-interview-dev-postgres-1
& './scripts/Start-DockerDesktop.ps1'
```

脚本先核对原库配置及已应用迁移版本，再使用本机Oracle JDK21、Maven Wrapper和Node22打包后端、执行前端check并构建两个镜像，按后端→前端启动。数据库须先启动；脚本不自动授权今后的新增迁移。新检出目录先按README准备npm ci；后端打包使用-DskipTests，不重跑产品套件或执行Maven clean。

若需更换前后端端口，显式指定两个不同的空闲端口，例如：

```powershell
& './scripts/Start-DockerDesktop.ps1' -FrontendPort 18090 -BackendPort 18091
```

更新、查看或停止前后端项目（保留卷；不停止原开发库）：

```powershell
& './scripts/Start-DockerDesktop.ps1'
docker compose --env-file .env -f compose.desktop.yaml ps
docker compose --env-file .env -f compose.desktop.yaml stop
```

不要删除数据卷或用down -v作日常停止。[compose.yaml](../compose.yaml)继续管理原开发数据库，未修改其配置；[compose.desktop.yaml](../compose.desktop.yaml)管理前后端。若更改前后端端口，保持DESKTOP_FRONTEND_PORT / DESKTOP_BACKEND_PORT值或继续传相同参数；数据库宿主端口仍由原compose.yaml管理。

## 2. 构建、配置与数据边界

| 文件/资源 | 本轮作用 |
| --- | --- |
| [后端Dockerfile](../backend/Dockerfile) | 将本轮打包的Boot JAR放入固定Java21运行镜像，UID10001非root运行 |
| [后端构建上下文](../backend/.dockerignore) | 仅允许指定JAR和Dockerfile，不发送源码、环境秘密、日志或证据 |
| [前端Dockerfile](../frontend/Dockerfile)及[上下文](../frontend/.dockerignore) | 将dist和Nginx配置组成静态运行镜像，nginx非root运行 |
| [Nginx配置](../frontend/nginx.conf) | 静态页面、Vue history刷新、同源/api和/actuator代理，保留原状态/Session/CSRF；关闭代理重试、缓存及磁盘缓冲，使用容器DNS解析后端地址 |
| [Compose](../compose.desktop.yaml) | 三服务健康依赖、只读应用镜像、tmpfs、独立卷和本机映射 |
| [启动脚本](../scripts/Start-DockerDesktop.ps1) | 本机构建及启动；新配置目录限制为当前Windows操作者，不覆盖已有密码，不输出配置秘密 |

宿主机实际构建为Oracle21.0.12、Node22.23.4；Linux运行镜像使用本机已有Temurin21.0.12.1+1及Nginx1.27.5，并在Dockerfile固定镜像digest。它们不替换Windows JDK或修改全局Java配置，不在镜像内安装另一套工程构建工具。数据库使用已确认的pgvector/pgvector:pg16，实际vector0.8.7。

脚本新建并保留`.local-data/docker-desktop/.env`，只包含本项目随机数据库密码；目录为当前操作者私有ACL，已由现有忽略规则排除。此处不是根`.env`，不读取、复制或注入百炼Key。Compose显式BAILIAN_EMBEDDING_ENABLED=false，容器实查Key未注入；也不接线生成或调用付费服务。

新卷为`ai-interview-desktop_desktop-postgres16-data`和`ai-interview-desktop_desktop-private-data`。数据库为新建空库，由应用Flyway首次应用V1～V8，未改迁移文件或迁移原开发库；私有文件在独立卷的`/var/lib/interview-coach/private`，实查10001:10001、0700，未作为静态目录公开。单活动应用执行者约束不变，不增加副本或绕过业务规则。

**新库账号数为0，没有默认账号/密码，也未复制开发账号或测试身份。** 后续需由维护者在受保护的Windows项目检出目录，通过既有受控开通命令操作明确的Desktop数据库目标；数据库连接由维护者本地受控注入，不把秘密写进聊天或脚本示例。Linux容器不绕过现有Windows维护预检，不直接插入账号或另造公开注册接口。参考[账号开通说明](../README.md#dt-02-受控账号开通与隔离验证)。本轮仅验证匿名入口/保护，不声称已登录完成资料或知识业务。

## 3. 实际执行及失败修正

开始分支`main`、HEAD `f22570d`，保留上一轮百炼适配、验证和文档未提交修改。本轮只增加容器配置/启动脚本及必要文档入口，没有改业务Java/Vue、依赖锁、迁移或根.env，不提交推送。

1. 配置首次校验时，清空进程密码变量产生空值并遮蔽专用env-file中的值。改为明确移除该进程变量；专用文件原密码保留，后续Compose config --quiet通过，未展示密码。
2. 后端打包及前端check通过，镜像构建成功；首次frontend启动因Nginx默认fastcgi临时目录位于只读镜像而退出。将fastcgi/uwsgi/scgi临时目录一并指向tmpfs，仅重建frontend并再次up --wait后，三个服务均healthy。未放宽只读或非root约束，未重建数据卷。
3. 普通执行环境访问本机端口受权限限制；在具备本机访问权限的环境核查HTTP通过。Chrome已生成截图/DOM，但辅助脚本在重定向文件仍打开时立即读取而报共享锁；随后按FileShare.ReadWrite读取原DOM，确认登录表单完整、没有身份未知提示，并目视核查原截图。不是页面或认证故障，不将辅助脚本的非零退出伪记为整脚本通过。
4. 收尾补上容器DNS动态解析，避免后端重建后代理保留启动时旧IP；仅重建前端并重复页面/history、代理健康、csrf、匿名401及缺csrf403核对，全部通过。没有为此再次打包后端或重跑产品套件。

后端本轮打包和前端check不是完整PF/VP测试；没有重新运行PG故障/并发/删除套件。仅新Desktop库的正常启动、结构、私有目录和匿名入口检查，不替代此前隔离测试或真实外部服务核实。

## 4. 核查结果与证据

| 检查 | 实际结果 |
| --- | --- |
| Compose运行状态 | frontend、backend、postgres均healthy，Docker Desktop项目名ai-interview-desktop |
| 页面及history路由 | /login和/app/knowledge-index直接访问均200；后者由Vue按匿名身份进入登录 |
| 后端/代理健康 | 18081直连及18080代理均UP |
| Session/CSRF | csrf200且有token及Session Cookie；证据只记录存在性，不保存token明文 |
| 匿名及缺CSRF | /api/auth/me为401；无CSRF的登录POST为403，未创建账号 |
| 真实Chrome | 登录标题、账号框、密码框存在；无“暂时无法核实登录状态”，截图已目视 |
| 新库只读审计 | V1～V8成功、vector0.8.7、账号0；不把空库当恢复/备份证明 |
| 运行边界 | 后端UID10001，私有目录0700，真实embedding禁用且Key未注入 |

证据保存在忽略目录`.cache/`：`docker-desktop-20261102-190404-e6d564.log`为后端/前端构建及首次启动失败；`docker-desktop-nginx-repair.log`为修正后前端构建及启动，`docker-desktop-dns-build.log`为最终DNS配置的构建及启动；`docker-desktop-http-smoke.json`、`docker-desktop-browser-smoke.json`、`docker-desktop-login.html`/`.png`、`docker-desktop-database-audit.log`为实际运行证据；`docker-desktop-deployment-final.json`绑定最终91项配置/源码、产物摘要和三容器健康状态，先前`docker-desktop-deployment.json`保持原快照。原日志/证据未删除或覆盖为成功。

Docker配置语义参考[Compose服务说明](https://docs.docker.com/reference/compose-file/services/)；代理设置参考[Nginx proxy模块](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)，2026-11-02核对。公共资料不替代本轮实际运行结果。

## 5. 下一步与开放项

现在可在Docker Desktop查看三服务并从本机打开前端。若要登录使用，再通过既有受控流程为这个新库开通账号；本轮不自动创建账号、扩展Linux维护能力或改变权限。Docker运行范围不推进DT-03、100例人工评审或真实百炼调用；原P2、账号DT-02补证、备份恢复、完整PF/VP、AI质量开放项保留。

## 6. 两项P2审阅后的镜像更新（2026-11-03）

按本轮授权完成审阅及更新，详细结论/命令/摘要/证据见[适配记录第8节](<百炼配置接线与向量模型适配：实现与验证记录.md#8-修补审阅与docker更新2026-11-03>)。新后端镜像包含两项修补及JDK启动三属性，容器JAR与本轮产物一致；前端构建内容相同，原容器复用。原PG/私有卷保留，V1～V8成功、账号0；未新增业务迁移。三服务healthy、HTTP/权限/CSRF及真实Chrome登录页通过。embedding保持false，无Key/真实调用，不将宿主历史71/26写为容器故障套件重跑。入口仍为18080/18081/15433，下一步账号开通须另按既有受控流程进行。

## 7. 改用已有开发库的配置与停止点（2026-11-03）

本节为获得迁移授权前的配置阶段历史；后续授权及实际结果见第8节。

用户明确选择连接compose.yaml已有interview_coach、使用已有账号数据。实际文件名是compose.desktop.yaml；未创建.yml别名。移除Desktop配置中的postgres服务，后端加入外部网络ai-interview-dev_default，连接ai-interview-dev-postgres-1:5432；DB_NAME/DB_USERNAME/DB_PASSWORD均按compose.yaml从根.env插值，解决原DB_PASSWORD与DESKTOP_DB_PASSWORD混用。原compose.yaml及根.env未修改；用户已有根.env只读挂载保留，不额外启用配置导入或embedding。

旧Desktop postgres容器、库及卷全部保留，不remove-orphans、不down -v、不把它挂到开发库。Desktop私有卷仍保留；后续若源库存在受控私有文件，必须核实文件根和原对象可达性，不能只连接数据库冒充文件迁移完成。新配置只管理前后端，开发postgres由原compose.yaml管理；脚本不再创建独立密码文件或支持DatabasePort参数。

已启动原开发postgres，并只读核实interview_coach：V1/V2成功、2账号；原开发卷ai-interview-dev_postgres16-data不变。当前源码需V3资料根/版本与屏障、V4 JD、V5私有上传、V6清理追踪、V7知识审核、V8索引构建。未获本次迁移授权，不启动连接该库的后端，不应用V3～V8；当前运行前后端仍连接旧Desktop库，不能宣称已使用这2个账号。

启动脚本先核对已有容器运行状态及与compose.yaml解析出的库名/用户/凭据一致性（不显示秘密），再只读核对已应用版本与仓库迁移版本；不匹配在打包/部署前停止。Compose config --quiet、PowerShell语法、git diff --check通过；实际调用脚本得到V1/V2与V1～V8差异并停止，记录.cache/desktop-shared-db-preflight-20261103.log。未运行产品套件、迁移、改变现有前后端容器或调用模型。

下一步需单独授权“备份并核实可恢复后，将已有interview_coach从V2追加迁移到V8，再切换Desktop后端并验证”。既有隔离PG迁移证据不代替这个目标库备份/恢复检查；保留2账号、不删旧库/卷/历史证据、不修改已应用迁移，embedding保持false。此前不迁移开发库的限制尚未解除，当前仅完成配置与门禁验证。

## 8. 授权备份恢复、原库升级及连接切换（2026-11-03）

用户明确回复“授权”，范围为先备份/隔离验证可恢复，再迁移原库V3～V8并切换Desktop后端。该授权覆盖本次原库迁移，不扩大为以后任意迁移、真实模型调用或产品验收。起点仍main@f22570d，保留已有修改、未提交推送，未改业务源码/迁移文件/根.env。

### 8.1 备份与恢复演练

运行本轮受控脚本`.cache/Migrate-AuthorizedDevDb-20261103.ps1`，12:52:55～12:53:20（UTC+8）完成备份、恢复、两次迁移。起点原库V1/V2、2账号，未发现其他数据库客户端。pg_dump -Fc生成完整数据库备份（包含真实账号散列/Session等敏感数据），保存在`.local-data/database-backups/devdb_v8_c1420e752453/interview_coach_before_v3_v8.dump`，SHA256为`A8F025DF96D9BBF58D7FA1F7D672FFEAADCD791ADDDE41CA65C9EAFF56B64117`。目录关闭ACL继承、仅当前Windows操作者可访问；备份文件核实仅一个允许主体，已被git忽略，不展示或提交正文。

新隔离PG16容器`devdb_v8_c1420e752453_pg`、卷`devdb_v8_c1420e752453_pgdata`及同前缀_network，无宿主端口；使用合成数据库密码，恢复真实备份。pg_restore --exit-on-error成功；identity_account、identity_account_authority、spring_session、spring_session_attributes、flyway_schema_history五张原表的行数/规范化行内容摘要全部一致。摘要用于本轮相等性核对，不作为密码安全或长期审计证明。

随后用当前生产镜像在隔离网络启动无端口的临时应用，embedding=false、无模型配置/Key挂载，Flyway追加V3～V8、validate通过、健康UP；账号及权限指纹与备份前一致。临时应用停止后复核原库五表仍未变化，才进入原库迁移。全部迁移文件原始SHA256前后相同，没有repair/clean、改旧SQL或改旧manifest。

### 8.2 原库迁移与运行切换

临时迁移应用连接ai-interview-dev-postgres-1/interview_coach，Flyway逐项应用V3～V8，共6个版本，成功到v8；日志验证成功后停止临时应用。原库2账号及权限记录（含原账号字段/密码散列/凭证代次）内容摘要不变。没有创建、重置、删除或输出账号/密码；原PG容器ID及ai-interview-dev_postgres16-data卷保持原身份。

再执行`& './scripts/Start-DockerDesktop.ps1'`，原版本门禁通过，后端打包（skipTests）、前端check、镜像构建和启动成功，12:54:32完成。容器实际DB_URL为`jdbc:postgresql://ai-interview-dev-postgres-1:5432/interview_coach`；只读pg_stat_activity在原库观察到来自新后端IP的10条连接，证明实际连接目标，不只检查配置文字。容器JAR与本轮打包产物摘要一致。

前后端及原开发PG均healthy；HTTP登录/history200、直连/代理健康UP、csrf200且存在Session/token、匿名me401、缺CSRF登录403；真实Chrome独立临时配置渲染登录页并目视截图。未使用真实账号密码登录，不能宣称登录后全部业务已验证。embedding仍false，没有真实供应商调用；用户已有根.env只读挂载保留，未新增SPRING_CONFIG_IMPORT，不能将“未注入Key环境变量”说成容器绝对不可读取挂载文件。

旧Desktop PG数据卷及私有卷仍存在、不迁移/删除。收尾未找到旧ai-interview-desktop-postgres-1容器，不能继续声称其当前存在；本轮没有删除容器的命令，旧卷仍核实保留。新隔离PG和两次临时迁移应用均停止且资源保留，隔离真实账号副本不对宿主开放端口。证据登记首次向PSCustomObject追加属性失败，仅影响登记，随后改字典保存成功；迁移及前述核查结果未回滚或重跑为虚假通过。

### 8.3 证据、边界与下一步

受控备份目录中的manifest.json绑定原库、卷、备份摘要、五表指纹、恢复/演练/迁移结果、源码摘要及最终部署；同目录保存两次临时应用日志。受控内容不进入版本库。`.cache/docker-desktop-20261103-125354-0c1334.json/.log`保存构建；`.cache/desktop-shared-db-final-audit-20261103.log`保存只读版本/账号数/连接数；`.cache/docker-desktop-20261103-shared-db-http-smoke.json`及同日期shared-db-login.html/.png、shared-db-chrome.log保存独立HTTP/Chrome结果。

本次证明目标数据库备份可恢复、V2到V8可追加升级及连接可用；没有备份集群角色/全局配置或真实私有文件，不证明完整备份恢复、30天保留、24小时故障耐久、独立删除账本/恢复开放门禁、完整PF/VP或AI质量通过。71/26仍为此前隔离产品套件结果，本轮未重跑。P3-DT-03～06及人工/账号政策/有限真实调用开放项不变。现在可用已有账号自行登录；后续仅按用户指定任务推进。
