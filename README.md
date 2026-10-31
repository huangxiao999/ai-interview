# AI 智能面试教练

当前进度（2026-10-31，P2-DT-05）：实时核对 PR #14 已合并，主线/本轮基线为 `035652a`，包含 P2-DT-01～04 及审查修复。当前分支 `p2-dt-05-cleanup-recover` 已完成本地清理恢复与阶段核查，17 项基础、28 项真实 PG/HTTP/Chrome、6 项持久化回归及前端 check 通过，待审阅/CI/复审。失败历史、最终证据和开放项见[资料清理恢复与阶段核查](<docs/P2-DT-05 资料清理恢复与阶段核查.md>)；完整 P2/MVP 未验收。下方“待提交/未合并”段落保留为历史时点，不代表当前主线。

清理验证在项目根执行 `& './scripts/Verify-P2Cleanup.ps1'`：新建隔离 PG16 库并使用真实合成文件及 Chrome，保留 manifest/日志/XML；不会迁移开发库。新增 V6 记录清理尝试与失败，不修改旧迁移。运维只读查询见 `infra/postgres/verify-cleanup.sql`，在明确目标数据库后通过 psql 执行；先确认该库已迁移 V6。30 天备份轮转、独立最新删除账本及恢复开放门禁尚未实证，不可据本地清理通过宣称可安全恢复旧备份。

历史导航审查修复（2026-10-31）：以已包含上轮修复的 `3ed6315` 为基线，补齐后退/前进的路由级协调和旧详情请求失效保护，实际验证见[记录第11节](<docs/P2-DT-04 页面历史与删除影响：实现与验证记录.md#11-审查修复历史导航阻止旧地址详情读取>)。本轮修改未提交/推送，不操作PR或进入P2-DT-05；以下状态保留为历史。

最新审查修复（2026-10-31）：P2-DT-04 的两项旧修复已包含在 PR #14 head `3f81678`，CI #37成功、旧意见已解决；本轮修复最新“资料目标与固定地址不同步”P2意见，新增/查看和保存/上传成功后同步地址，并保留未决操作提示。验证与失败历史见[记录第10节](<docs/P2-DT-04 页面历史与删除影响：实现与验证记录.md#10-审查修复资料目标与固定地址同步>)。本轮修改未提交/推送，未操作PR或进入P2-DT-05；下文旧状态保留为各次实施历史。

后续审查状态（2026-10-31）：P2-DT-04 原实现已提交 `400396f` 并创建 PR #14，当前仍未合并。同根详情恢复修复见[记录第8节](<docs/P2-DT-04 页面历史与删除影响：实现与验证记录.md#8-审查修复同根详情重读保留未决版本入口>)，删除未知结果时核实入口被模态框遮挡的修复见[第9节](<docs/P2-DT-04 页面历史与删除影响：实现与验证记录.md#9-审查修复删除未知结果核实入口可达>)；两项修复尚未提交，仍待复审。下文“未提交/未创建PR”描述属于初次实施历史。

当前补记（2026-10-31）：P2-DT-03 及解析规范化审查修复已由 PR #13 合入主线 `c4e12ec`。P2-DT-04 已在独立分支本地完成桌面资料准备、版本历史、草稿离开提醒和删除清理状态，17项基础/文件、24项隔离PG/HTTP/Chrome、1项登录恢复回归及前端check通过，尚未提交或创建PR。范围、命令、失败历史及证据见[本任务记录](<docs/P2-DT-04 页面历史与删除影响：实现与验证记录.md>)。以下原切片段落和测试数量保留各自时点，不代表前置任务仍待合并。

当前账号切片 DT-01～07 已完成各自范围，DT-02 的少量独立补证仍开放。P2-DT-01 经 PR #10/#11、P2-DT-02 经 PR #12 合并。P2-DT-03 已在独立分支实现 PDF/DOCX 上传、限时解析、人工核对确认版本、鉴权原文件读取及当前文件清理恢复，本地实现与验证完成，待审阅、CI 与独立复审。计划、面试、评价报告、额度和完整关联删除未实现；完整 P2/FS-A/ACC/PF/VP 未验收。

随后DT-01已完成：接入Flyway与Session JDBC，开发库app schema已迁移到V2；4项基础测试、6项真实PG集成检查及打包双模式启动通过，见 [DT-01记录](<docs/DT-01 版本化持久化基础：实现与验证记录.md>)。已有账号/权限/Session基础表不代表已实现登录业务。

DT-02已实现M-01最小账号仓储、版本化密码散列、Actor及本地受控开通；独立审核提出的两项 P2 已修复并完成本地复测。2026-10-29 的新隔离 PG16 补证发现并修复 Windows Junction 路径遗漏；实际 Spring 装配事务、并发、主要拒绝及输入散列已有证据，**真实跨用户所有权、NTFS 符号链接和读回不可用仍未执行**，见[DT-02记录](<docs/DT-02 账号与受控开通：实现与验证记录.md>)。此前环境与DT-01历史见[环境验证](docs/开发环境调整与基础验证.md)及[开发计划](docs/开发计划.md)。

DT-03已实现后端 `csrf/login/me/logout`、30分钟空闲会话、CSRF、登录时会话ID轮换、每请求凭证代次核实及 JSON 错误；真实PG16与Chrome验证范围见[DT-03记录](<docs/DT-03 会话认证与最小API：实现与验证记录.md>)。DT-04已实现受控密码重置，新散列与凭证代次共同提交，旧会话立即逻辑失效；适用证据见[DT-04记录](<docs/DT-04 重置与会话撤销：实现与验证记录.md>)。DT-05提供真实登录页面及受保护的空工作区，适用证据见[DT-05记录](<docs/DT-05 最小登录前端：实现与验证记录.md>)。DT-06补齐本片的回包丢失、双窗口切换、会话过期及返回路径验证，证据见[DT-06记录](<docs/DT-06 浏览器与恢复闭环：实现与验证记录.md>)。DT-07对账号入口、规则所有权、迁移及 FS-A/ACC 覆盖范围作了[审查与证据登记](<docs/DT-07 账号切片审查与证据登记.md>)；没有私有资料接口，私有资料隔离尚未验收。

需求和设计基线见 [AGENTS.md](AGENTS.md) 的文档入口。CF-03 的服务端展示确认计次口径保持有效；CF-04 仅使用已审核知识库 RAG 与模型生成/评价，不接入实时搜索、网页抓取或模型内置联网。本工程未接入模型或向量化服务。

P2「资料与版本」的[详细设计](<docs/P2 资料与版本详细设计.md>)与[独立开发任务](<docs/P2 资料与版本开发任务.md>)已按用户审阅的范围开始实施。[首个正文切片](<docs/P2-DT-01 粘贴简历私有闭环：实现与验证记录.md>)与[JD及版本切片](<docs/P2-DT-02 JD确认与资料版本：实现与验证记录.md>)分别登记证据；P2 阶段未完成。

## 文档编号速查

`docs/` 中的编号用于把需求、设计、开发任务和验证证据相互关联；编号出现不表示对应功能已实现或验收通过。以下是本项目使用的前缀，不是通用行业标准：

| 前缀 | 含义与示例 | 主要查看位置 |
| --- | --- | --- |
| `PRD` / `SCP` | 产品需求 / MVP 范围项，如 `PRD-01`、`SCP-01` | [PRD](docs/产品需求.docx)、[SCOPE](docs/交付范围.docx) |
| `ACC` / `DACC` | 原需求验收案例 / 产品设计补充的专项验收案例，如 `ACC-01-01`、`DACC-01-01` | [ACCEPTANCE](docs/验收标准.docx)、[设计追溯](docs/需求与设计追溯.md) |
| `CF` | 后续明确确认的业务规则或范围补充，如 `CF-03` 首题展示确认口径 | [产品流程](docs/产品流程与状态设计.md)、[技术决策](docs/技术决策记录.md)；以具体确认记录为准 |
| `FL` / `PG` / `OV` / `IX` | 用户流程 / 页面 / 弹层或状态面板 / 交互规则，如 `FL-04`、`PG-04` | [产品流程](docs/产品流程与状态设计.md)、[交互设计](docs/页面与交互设计.md) |
| `RK` / `ST-I`、`ST-R`、`ST-Q` / `OP` | 设计风险分支 / 面试、报告、额度状态 / 维护与验证事项 | [产品流程](docs/产品流程与状态设计.md) |
| `G` / `M` / `AG` / `BR` / `UC` | 业务词汇 / 职责模块 / 候选聚合 / 业务规则 / 用例协调者 | [领域模型](docs/轻量领域模型与模块职责.md)、[一致性边界](docs/业务协作与一致性边界.md) |
| `CB` / `SC` | 必须守护的一致性边界 / 并发与事件顺序场景 | [一致性边界](docs/业务协作与一致性边界.md) |
| `TD` / `SD` | 技术决策项 / 首题展示方案候选，如 `TD-01`、`SD-05`；**不同于开发任务 `DT`** | [技术决策](docs/技术决策记录.md) |
| `PF` / `VP` | 待证明的实现要求 / 对应的可执行验证场景；计划写好不等于已执行 | [一致性边界](docs/业务协作与一致性边界.md)、[证明计划](docs/实现证明计划.md) |
| `FS-C` / `FS-A` | 首个账号切片的已确认补充 / 该切片的验收场景，如 `FS-C01`、`FS-A12` | [首片设计](docs/首个业务切片详细设计.md) |
| `DT` | **开发任务**：把切片拆成可独立实施和检查的工作项，如 `DT-01` 持久化基础、`DT-02` 账号拥有者；`DT-01～07` 各自完成后仍须按证据判断首个账号切片验收，不代表整个 MVP 完成 | [开发计划](docs/开发计划.md)、[DT-07证据登记](<docs/DT-07 账号切片审查与证据登记.md>) |
| `P2-DT` | P2 资料与版本的独立开发任务，如 `P2-DT-01` 粘贴简历私有闭环；逐项按实现证据判断 | [P2 任务](<docs/P2 资料与版本开发任务.md>) |
| `EV` / `DV` | 实际环境验证 / DT-01 持久化验证结果编号，如 `EV-01`、`DV-01` | [环境验证](docs/开发环境调整与基础验证.md)、[DT-01验证](<docs/DT-01 版本化持久化基础：实现与验证记录.md>) |

阅读某个编号时，先看其来源文档的规则和状态，再看对应实现与验证记录；不能仅凭测试命令成功推断整个 MVP 已完成。

## 工程结构

```text
backend/
  pom.xml、mvnw、mvnw.cmd、.mvn/wrapper/   后端构建与 Maven Wrapper
  src/main/java/dev/interviewcoach/
    InterviewCoachApplication.java       启动入口
    bootstrap/                            初始化访问边界
  src/main/resources/                      通用、bootstrap、local 配置
    db/migration/                          V1账号/权限、V2官方Session、V3简历正文与删除屏障
  src/test/java/dev/interviewcoach/         启动与访问边界基础测试
frontend/
  src/api/                                 认证/资料请求适配与错误类型
  src/router/、stores/、views/、assets/       受保护路由、Pinia、登录/工作区页面与样式
  package.json、package-lock.json            固定依赖及检查脚本
infra/postgres/001-vector.sql、verify.sql   仅创建扩展及独立只读验证
scripts/Use-LocalTools.ps1                  当前终端的开发工具与缓存配置
scripts/Verify-Persistence.ps1             新建隔离PG16测试库并运行DT-01检查
scripts/Verify-Account.ps1                 新建隔离PG16测试库并运行DT-02检查
scripts/Verify-SessionAuth.ps1             新建隔离PG16测试库并运行DT-03 HTTP/Session检查
scripts/Verify-SessionBrowser.mjs          DT-03真实Chrome Cookie与CSRF检查，由验证脚本间接运行
scripts/Verify-PasswordReset.ps1           新建隔离PG16测试库并运行DT-04重置/竞态检查
scripts/Verify-ResetBrowser.mjs            DT-04真实Chrome双标签撤销检查，由验证脚本间接运行
scripts/Verify-LoginFrontend.ps1           新建隔离PG16测试库并验证DT-05实际页面
scripts/Verify-LoginBrowser.mjs            DT-05真实Chrome页面检查，由验证脚本间接运行
scripts/Verify-P2Resume.ps1                新建隔离PG16测试库并运行P2-DT-01检查
scripts/Verify-P2ResumeBrowser.mjs         P2-DT-01真实Chrome正文/删除检查，由脚本间接运行
scripts/Protect-ProvisioningWorkspace.ps1  收紧当前Windows检出目录的维护权限
scripts/Provision-TrialAccount.ps1         仅交互式本地受控开通试用账号
scripts/Reset-TrialAccountPassword.ps1     仅交互式本地受控重置试用账号
compose.yaml                               可选的本地开发数据库
docs/                                      既有需求、产品与技术设计
```

领域 M-01～07 的职责及写入所有权仍以[领域模型](docs/轻量领域模型与模块职责.md)为准。Java已增量建立`identity`模块及 P2-DT-01 的`material`正文私有闭环；没有批量创建其他模块空分层。Spring JDBC、Flyway与Session JDBC均由Boot管理；RestClient和持久任务未实现。

## 环境与版本

核实日期：2026-10-28。依赖以构建文件和锁文件为准。

| 能力 | 本工程版本或约束 |
| --- | --- |
| Java | 使用当前完整JDK21；本机实际Oracle 21.0.12，不强制切换Temurin，不使用预览特性 |
| Spring Boot | 4.1.2；MVC、JDBC、Security、Actuator 统一使用 Boot 依赖管理 |
| Maven / Wrapper | 3.10.0 / 3.3.4；Wrapper 固定官方分发地址及 SHA-256 |
| Node / npm | 22.23.4 / 10.9.9；项目要求 Node 22.x 且不低于 22.18 |
| Vue / Vue Router / Pinia | 3.5.44 / 5.4.0 / 4.1.0 |
| TypeScript / Vite / Vue 插件 | 6.0.3 / 8.3.0 / 6.0.10 |
| 静态检查 | ESLint 10.13.0、typescript-eslint 8.72.1、Prettier 3.9.10；其余见 package.json |
| PostgreSQL / pgvector | 镜像 `pgvector/pgvector:pg16`；本轮实际16.15 / 0.8.7，digest见环境验证文档 |
| Flyway / Spring Session JDBC | 12.4.0 / 4.1.1；Boot统一管理，Session官方PostgreSQL结构由V2迁移 |

TypeScript 未采用注册表中的最新大版本：当前 typescript-eslint 的声明兼容范围为 `<6.1.0`，因此固定 6.0.3，并已通过类型及静态检查。npm 依赖使用精确版本与锁文件，后续使用 `npm ci`。PG16镜像已实际拉取，标签仍为用户指定的可更新标签；记录实际digest用于核对，不承诺后续拉取相同版本。

官方依据：[Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Maven Wrapper](https://maven.apache.org/tools/wrapper/)、[Vue 环境前提](https://vuejs.org/guide/quick-start.html)、[Vite 版本](https://vite.dev/releases)、[pgvector 镜像与扩展](https://github.com/pgvector/pgvector)、[typescript-eslint 依赖声明](https://registry.npmjs.org/typescript-eslint/8.72.1)。[Temurin 元数据](https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse)仅保留初始化历史来源，当前不强制此供应商。版本背景见 [技术方案](docs/技术方案设计.md)。

### 当前终端的工具配置

以下命令适用于 Windows 上的 PowerShell 7（本次 7.6.5），初始目录为项目根目录。首次获取 Wrapper 和 npm 依赖需要访问官方仓库；不要求全局 Maven。

```powershell
# 执行目录：项目根目录；每个新的终端各执行一次
& './scripts/Use-LocalTools.ps1'
java -version
node --version
npm.cmd --version
```

脚本读取当前PATH上的完整JDK21，将当前进程的 `JAVA_HOME` 和Java工具路径对齐它，不再自动切换到便携Temurin。缓存和临时目录也只影响当前进程，不修改系统环境。便携Node仍可优先使用本项目 `.tools/` 版本；工具与 `.cache/` 不属于源码交付，换机器自行准备JDK21及符合要求的Node22，脚本不自动下载安装。当前Java可执行文件所在目录必须包含javac，不能只指向JRE或外部shim目录。

当前机器全局 Node 22.14.0 不满足项目要求，因此使用便携 Node 22.23.4。项目短临时目录还用于避免当前 Windows 执行环境下 Java NIO 临时 socket 路径过长造成的启动失败。Wrapper 首次下载、依赖仓库和 npm 缓存均保留在项目忽略目录中。

初始化历史记录：先前从Adoptium取得便携Temurin21.0.12.1+1，验证SHA-256为 `f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e` 并完成当时检查；该工具及记录保留。随后用户明确改为使用本机现有JDK，本轮Oracle21.0.12构建与local启动另已验证，不把两个运行时的历史记录混写。

## 启动与基础检查

### 后端：终端一

```powershell
# 执行目录：项目根目录
& './scripts/Use-LocalTools.ps1'
Set-Location backend

# 执行目录：backend；构建并运行六项基础测试，不执行数据库集成检查
./mvnw.cmd '-Dmaven.repo.local=../.cache/maven-repository' -B -ntp verify

# 执行目录：backend；启动已构建产物
java -jar target/interview-coach-backend-0.0.1-SNAPSHOT.jar
```

默认使用 `bootstrap` profile，明确排除 DataSource 自动配置，便于不依赖数据库检查工程启动。`UP` 只说明应用运行，不证明 PostgreSQL、pgvector、模型或业务可用。

后端仅监听 `127.0.0.1:8080`。默认 `bootstrap` 模式只开放 GET 健康端点，不返回组件或敏感明细；其余请求拒绝访问。**体验登录页面需要启动真实数据库的 `local` 模式**，提供 DT-03 后端认证 API 并保留 CSRF；没有框架默认登录页或公开注册。受控开通仍仅是本地维护命令。

```powershell
# 执行目录：backend；先按下文准备 .env 与 PG16，并完成构建
java -jar target/interview-coach-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

重新构建前先停止使用同一 JAR 的进程；Windows 下重打包运行中的文件会失败。

```powershell
# 执行目录：任意目录；另开终端检查运行中的后端
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -NoProxy
```

### 前端：终端二

```powershell
# 执行目录：项目根目录
& './scripts/Use-LocalTools.ps1'
Set-Location frontend

# 执行目录：frontend
npm.cmd ci --no-audit --no-fund
npm.cmd run check
npm.cmd run dev
```

用 `local` 后端启动后，打开 <http://127.0.0.1:5173/>。页面经 `/api` 开发代理读取真实身份，匿名进入登录页；已有受控开通账号可登录、查看工作区占位并退出。默认 `bootstrap` 后端不提供认证接口，不能用于登录页面联调。两端只监听本机，端口被占用时启动失败，不静默切换端口。停止各服务使用对应终端的 Ctrl+C。

`npm run check` 包含 ESLint、Prettier 检查、Vue/TypeScript 类型检查和 Vite 构建；可分别运行 `npm run lint`、`npm run format:check`、`npm run type-check`、`npm run build`。`npm run format` 会实际格式化前端文件。

如需修改后端端口，在后端终端启动前设置 `$env:SERVER_PORT='8081'`，在前端终端启动前设置 `$env:BACKEND_URL='http://127.0.0.1:8081'`。这是开发配置，不是业务开关；该自定义端口组合尚未实测。前端健康请求的 5 秒超时只用于本初始化检查，不代表业务模型调用等待规则。不要把秘密放入 `VITE_*` 变量或前端构建产物。

## 本地数据库与版本迁移

本轮Docker Engine可用，PG16容器已启动并完成真实连接、扩展和local JDBC检查。后续执行前仍检查实际Engine及端口状态。Compose仅供本地开发，不是生产部署方案，不使用H2替代真实数据库。独立卷为 `postgres16-data`，挂载 `/var/lib/postgresql/data`，不复用PG18目录、不删除旧卷。

```powershell
# 执行目录：项目根目录；仅在尚无 .env 时复制，不覆盖已有文件
if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
}
# 新复制的 .env 须用编辑器修改 DB_PASSWORD 占位值；已有文件不覆盖。
# 确认 Docker Engine 已运行后：
docker compose config --quiet
docker compose up -d --wait postgres
```

`.env` 同时供 Compose 与后端 `local` profile 使用，采用 UTF-8 的简单 `KEY=VALUE` 行，不使用 shell 引号、命令替换或多行秘密；当前开发示例不支持任意 dotenv 语法。不要打印完整配置或提交实际文件。环境变量可覆盖同名配置。默认连接 `127.0.0.1:15432/interview_coach`；修改 `DB_PORT` 或 `DB_NAME` 时须同步修改 `DB_URL`。

本地数据库使用持久命名卷。Compose初始化脚本仅在全新目录创建vector扩展；已有卷不会重新执行，不要删除数据卷补跑。DT-01已采用Flyway：启动local时自动校验并迁移app schema，V1账号/权限、V2Session及Flyway历史共5表。Spring SQL和Session自动建表均禁用，迁移是结构唯一管理者。不能修改已应用文件或自动baseline/clean/repair未知结构。

local使用Hikari schema=app，使应用JDBC与Session仓储在同一schema工作，Flyway显式schemas/default-schema=app。无需改写已有DB_URL或.env。更换数据库目标前必须核对所有者、既有schema/表及迁移历史，未知目标不可直接接管。公共vector类型/运算符在应用连接中使用public限定，原扩展不搬迁。

```powershell
# 执行目录：项目根；本轮已执行，只读查询版本、向量常量和非系统表数量
Get-Content -LiteralPath infra/postgres/verify.sql -Raw | docker compose exec -T postgres sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -A -t'

# 执行目录：backend；需已配置根目录 .env，且数据库已就绪
# 与 bootstrap 相比，local 启用真实 DataSource；本次8080被其他进程占用，显式选择18080
$env:SERVER_PORT='18080'
java -jar target/interview-coach-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

SQL命令使用容器自己的已配置账号，不打印密码。另一个终端可请求 `http://127.0.0.1:18080/actuator/health`。此前EV阶段表数量0的历史保留；DT-01后非系统普通表总数5，四张账号/权限/Session表数据量均0。使用 `infra/postgres/verify-persistence.sql` 可只读核对迁移与表/数据清单，不再要求表数永远为0。

未来私有文件规划为根目录下的 `.local-data/private/` 受控持久目录，位于 Web 静态资源目录之外，已忽略整个 `.local-data/`；本轮未创建用户目录或上传入口，未接入存储配置。接入时由后端鉴权访问，不能放入 `frontend/public` 或静态映射。日志、备份、秘密配置、构建产物及工具缓存也已配置忽略规则。

## DT-02 受控账号开通与隔离验证

登录名按原样区分大小写精确匹配，仅允许3～64位ASCII字母、数字、`.`、`_`、`-`。初始密码由系统随机生成，仅在成功开通后向本地交互控制台显示一次；操作者通过批准的线下渠道交付，勿开启终端转录或将其复制进文档、日志、工单。没有公开注册或重置入口；后端登录 API 已由 DT-03 实现，登录页面由 DT-05 提供。

本地开通要求当前 Windows 操作者拥有项目根、`.env`、脚本和已构建 JAR，且这些关键路径只允许该操作者、Administrators 和 SYSTEM 访问。新检出目录先构建 JAR，再由项目所有者检查并执行以下一次性权限加固；脚本只修改当前检出目录根 ACL，旧 ACL 备份在被忽略的 `.cache/`。若子项另有共享权限，脚本会拒绝并要求人工核查。不要在共享检出目录或持有真实凭证的他人目录执行。

```powershell
# 执行目录：项目根；先人工检查本地ACL，再由当前检出目录所有者加固
& './scripts/Protect-ProvisioningWorkspace.ps1'

# 执行目录：项目根；需要已构建后端JAR、local数据库配置及交互控制台
& './scripts/Use-LocalTools.ps1'
& './scripts/Provision-TrialAccount.ps1' -LoginName 'synthetic.user'

# 执行目录：项目根；每次建立并保留独立PG16测试库，使用合成账号
& './scripts/Verify-Account.ps1'
```

修复阶段的本地复测中，6项基础测试、6项真实PG集成测试及交互式命令通过；宽权限与非交互路径均在 Flyway 前拒绝，隔离库无迁移和账号写入。独立复审当时仅只读核对源码、权限和既有报告。2026-10-29 另行运行 `& './scripts/Verify-Account.ps1'`：新隔离库6项基础测试通过、15项真实PG集成检查中14项通过且1项符号链接场景因权限跳过；脚本清单明确标为 `passed-with-skips`。过宽 ACL、Junction 和缺失脚本的交互式启动也在隔离夹具验证拒绝。详细证据、未执行项和 JAR 摘要见[DT-02记录](<docs/DT-02 账号与受控开通：实现与验证记录.md>)。该 ACL 边界仅适用于当前 Windows 本机维护，不替代正式环境的运维授权。测试库中的合成账号不得当作产品试用凭证。

## DT-03 后端会话认证验证

`local` 模式现提供 `GET /api/auth/csrf`、`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout`。登录使用已有受控开通账号；密码重置只经本地受控命令，不提供公开重置接口或产品页面。

```powershell
# 执行目录：项目根；每次创建并保留独立 PG16 测试库，运行真实 HTTP/Session 和 Chrome 检查
& './scripts/Verify-SessionAuth.ps1'
```

DT-03 最新隔离回归的 9 项集成检查及 Chrome 浏览器检查结果见[DT-03记录](<docs/DT-03 会话认证与最小API：实现与验证记录.md>)。脚本使用合成账号，不修改开发账号数据；默认后端 `test` 另有 6 项基础测试通过。这些结果不覆盖 DT-05 产品前端、真实私有资源隔离或完整 PF/VP。

## DT-04 受控密码重置与撤销验证

先在受保护的本机 Windows 检出目录构建最新 JAR，并按 DT-02 的维护权限约定检查当前所有者与 ACL。重置需要开通时返回的账号 UUID；只在交互控制台显示一次新随机密码，经受控线下渠道交付。不要转录、重定向或分享包含密码的控制台输出。

```powershell
# 执行目录：项目根；使用真实试用账号时会使其全部旧登录会话失效
& './scripts/Reset-TrialAccountPassword.ps1' -AccountId '<账号UUID>'

# 执行目录：项目根；只使用新建的隔离PG16库及合成账号
& './scripts/Verify-PasswordReset.ps1'
```

最新 6 项基础测试、6 项真实 PostgreSQL/HTTP 集成及 Chrome 双标签检查通过；受控交互命令已在隔离合成账号上验证，结果与先前测试脚本失败记录见[DT-04记录](<docs/DT-04 重置与会话撤销：实现与验证记录.md>)。重置后的旧会话以账号凭证代次立即失效；Session 物理清理是尽力执行，不把清理行数当撤销是否成功的判据。

## DT-05 最小登录前端验证

```powershell
# 执行目录：项目根；每次创建并保留独立 PG16 库，启动临时后端、Vite 与真实 Chrome
& './scripts/Verify-LoginFrontend.ps1'
```

前端登录、身份核实、受保护空工作区与退出已可操作；测试使用随机合成账号，不改开发账号数据。`frontend/` 下 `npm.cmd run check` 通过；隔离库中 6 项基础测试和 1 项真实页面集成测试通过。详细场景、首次中断记录及证据见[DT-05记录](<docs/DT-05 最小登录前端：实现与验证记录.md>)。此段仅记录 DT-05 当时的检查；私有资源隔离及完整 FS-A/PF/VP 仍未完成。

## DT-06 浏览器与恢复验证

```powershell
# 执行目录：项目根；每次新建并保留隔离 PG16 库，启动临时后端、Vite 和真实 Chrome 双标签
& './scripts/Verify-LoginRecovery.ps1'
```

本轮前端 `npm.cmd run check` 及最终隔离 PG16/Chrome 集成验证通过。测试覆盖登录/退出回包丢失后核实、多窗口切账号与迟到响应、数据库暂不可用、会话过期及返回路径限制；最终证据见[DT-06记录](<docs/DT-06 浏览器与恢复闭环：实现与验证记录.md>)。测试不使用真实用户资料，保留隔离测试库；私有对象直接访问隔离和完整 PF/VP 仍待后续验证。

## P2-DT-01 粘贴简历正文验证

P2-DT-01 已实现本人粘贴正文、确认不可变版本、列表/详情和整份删除，并已合并。历史 9 项验证及审查修复见[原记录](<docs/P2-DT-01 粘贴简历私有闭环：实现与验证记录.md>)，其历史“待提交”描述保留。本轮 P2-DT-02 继续扩展同一脚本与记录方式。

## P2-DT-02 JD 与历史版本验证

根目录先执行 `& './scripts/Use-LocalTools.ps1'`，确认本项目 PG16 容器可用后执行 `& './scripts/Verify-P2Resume.ps1'`；该脚本现覆盖 P2-DT-01/02，仍使用新建并保留的 `p2dt01_verify_*` 隔离库。2026-10-31 的最终 15 项真实 PG/HTTP/Chrome 检查通过，覆盖 V3 数据升级 V4、JD 边界、两类历史版本、幂等、身份与 CSRF、重置后迟到写、删除共同回滚及双向竞争。`frontend/` 下 `npm.cmd run check` 与后端 6 项基础测试通过。接口、失败历史与证据见[本任务记录](<docs/P2-DT-02 JD确认与资料版本：实现与验证记录.md>)。

页面在选择本人简历后提供简历/JD 版本列表、固定版本 ID、基于所选版本编辑、确认追加及原操作核实。新增 V4；2026-10-31 开发库只读检查仍为 V1/V2、尚无简历/JD 表，本轮未对开发库迁移。首次本地启动新版本的 `local` 模式前须核查目标库及 Flyway 历史，应用将按既有配置迁移到 V4；不能将隔离库通过写成开发库已迁移。

## DT-01隔离集成验证

```powershell
# 执行目录：项目根；本项目PG16服务须就绪，已有.env不覆盖
& './scripts/Verify-Persistence.ps1'
```

已实际执行通过。脚本每次在本项目PG服务创建独立测试数据库，准备vector后运行Maven的database-verification profile，不在开发账号数据上做故障注入。每个测试库保留供核查，日志及manifest见 `.cache/dt01-test-run.json` 所指文件，报告在 `backend/target/failsafe-reports/`。重复执行会新增测试库，不自动删除；不要使用down -v清理。

当前默认 `verify` 运行4项bootstrap和2项DT-02密码/Actor基础测试；上述DT-01脚本另运行6项集成检查，覆盖首次/重复迁移、约束、真实Session仓储、V2失败回滚/恢复及未知schema拒绝。没有隔离库环境变量时直接启用该profile会失败，不静默跳过或改用开发库。脚本当前只支持文档中的本地JDBC URL（无查询参数），不修改.env中的连接目标。

完整结果及故障日志解释见 [DT01_VERIFICATION](<docs/DT-01 版本化持久化基础：实现与验证记录.md>)。这不是登录、FS-A12全案例、PF/VP或AI验收通过。

## 初始化历史验证与当前限制

以下表格保留2026-10-28先前Temurin初始化阶段的实际记录，**不是后续Oracle/PG16调整的当前状态**。后续结果单独记录在 [ENVIRONMENT_VERIFICATION](docs/开发环境调整与基础验证.md)：Oracle构建3项通过、PG16.15/vector0.8.7/向量常量/local JDBC通过；PF/VP及业务验收仍未执行。日志和构建产物位于忽略目录，无真实用户资料。

| 检查 | 结果与边界 |
| --- | --- |
| Maven Wrapper 版本及下载校验 | Maven 3.10.0，分发包 SHA-256 固定，实际下载成功 |
| 后端 `verify` | Temurin 21.0.12.1+1 下构建成功；3 项启动/健康/默认访问边界测试通过，详见 `backend/target/surefire-reports/` |
| 打包 JAR 启动及健康请求 | 已启动，GET 健康接口返回 `UP`，未连接数据库 |
| npm 锁文件重复安装 | `npm ci` 成功 |
| 前端静态、格式、类型检查及构建 | `npm run check` 全部通过；未执行完整产品浏览器验收 |
| 前端开发服务与代理 | 入口 HTTP 200，含应用挂载点，代理健康接口返回 `UP`；不等同于真实浏览器交互证明 |
| Compose 配置解析 | 使用临时非敏感占位变量执行 `docker compose config --quiet` 成功，未启动容器 |
| PostgreSQL、pgvector | Docker Engine 未运行；未拉取镜像、未创建数据库、未核实连接或扩展 |
| 模型、向量化与账号能力 | 未接入、未调用，留存、价格、实际配额等实施核实未执行 |
| PF/VP、业务与 AI 质量验收 | 未执行，不能将本轮基础检查记为通过 |

上表记录产生时本目录尚未初始化 Git；当前已有 Git 仓库。本轮 DT-03 没有提交、推送或部署。

## 下一阶段

1. DT-01已完成；DT-02两项 P2 修复已本地复测并完成独立复审，部分验证证据待补。登录名和初始凭证规则已确认，Flyway/app schema及现有表结构不再是待选项。
2. DT-03～07 已完成各自范围，[DT-02待补证据](<docs/DT-02 账号与受控开通：实现与验证记录.md>)继续开放。P2-DT-01/02 已合并；本轮审阅 P2-DT-03，提交后通过 CI 与独立复审、合并，再进入 P2-DT-04。
3. 将 [TD 决策](docs/技术决策记录.md) 的关键协作落到可实现协议，特别是 CF-03、操作恢复、报告/额度共同提交和删除屏障；按 [证明计划](docs/实现证明计划.md) 安排真实持久化、故障与浏览器验证，禁止用当前健康测试替代。
4. 按需求追溯实现账号与资料等小步闭环，再逐步接入已审核知识库和模型适配；服务账号、资料审核、评分样例及留存备份能力另行核实。

本轮 P2-DT-03 到此停止，后续能力按独立任务推进。

2026-10-31 审查修复补记：PR #13 已创建，原提交 `07c2bea` CI通过；解析正文先统一换行、去首尾空白再检查码点上限的修复已在本地通过17项基础/文件及24项隔离PG/HTTP/Chrome检查，修复尚未提交、推送或独立复审。证据见[P2-DT-03记录第8节](<docs/P2-DT-03 受控文件与解析：实现与验证记录.md#8-p2-审查修复解析正文先规范化后计数>)，下文9/23项属于初次实施历史，不代表本次最新测试数量。

## P2-DT-03 文件与解析验证

[实现、接口与验证记录](<docs/P2-DT-03 受控文件与解析：实现与验证记录.md>)。项目根目录执行 `& './scripts/Verify-P2Upload.ps1'`，复用原资料脚本，每次新建并保留 `p2dt01_verify_*` 测试库，执行9项基础/文件检查、打包和23项真实PG16/HTTP/Chrome检查；`frontend/` 下执行 `npm.cmd run check`。2026-10-31最终均通过，另有6项持久化回归通过，证据见记录第5节。30秒真实挂起子进程验证需等待其截止，整套本地约需两分钟。

仅追加 V5。开发库本轮只读仍为 V1/V2、2个账号、0张资料表；启动新版本 local 前核实目标库，Flyway 将应用 V3～V5。不要把测试库迁移当作开发库已迁移，不执行 clean/baseline/repair。持久化基础脚本已按 V5 更新清单并限定自己的测试集合。

从 backend 目录使用 local 配置启动。根 .env 可设置 `PRIVATE_FILE_ROOT=../.local-data/private`，路径相对 backend；此目录不能位于静态资源下。新目录创建私有权限；已有目录权限过宽或含 Junction/符号链接时拒绝启动，维护者应核实受控目录。数据库/模型秘密不传给解析子进程。私有暂存、解析缓存和原文件不提交 Git。仅允许一个活动应用执行者使用该目录；解析失败后可放弃并粘贴正文，刷新后可通过“查找未完成上传”恢复。完整页面布局、备份及24小时长期证明仍属后续任务。
