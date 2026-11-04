# 真实生成/query接线与受控试验门禁：实现与验证记录

日期：2026-11-04。独立工程切片，分支`codex/p4-controlled-trial-wiring`。真实请求0；未部署、未迁移开发库、未提交推送。本记录不关闭P3/P4、完整PF/VP、账号DT-02、P2、长期备份恢复或AI质量开放项。

## 1. 基线与授权

当前基线`ee2b445c8559b50017fef71f63d7033f635aad18`，GitHub实时main同提交，含PR #26、#27；此前核实两项CI成功、正式reviews与review threads为空，不能写成人工批准/安全专项通过。开始时9份DT-04材料及用户确认文档已有未提交修改，本次明确携带保留，原文副本及SHA256位于`.cache/controlled-wiring-20261104/before/`和`inherited-documents.json`。

授权来源：[DT-04材料第10节](<P4-DT-04 真实用途材料与有限试验准备.md#10-用户审核与有限调用授权确认2026-11-04>)。用户已确认材料及10份计划正文、一次有限调用建议，无需重复批准相同范围。本次工程指令不自动开始该真实窗口。

本切片选其更小子集：**复用已核实的同供应商/地区/业务空间document索引，D=0、Q≤1、G≤1、串行1**。不实施document新构建窗口；若没有合格索引，真实执行必须停在装配，不启用旧维护调度器补索引。原D≤4/总≤6授权上限没有被扩展或消费。

隔离测试manifest中的`REF-P01/v0.1`只作字段协议夹具，其合成正文不是该人工参考例的真实运行，不登记为参考例质量结果；100例规划、32份正文、10份计划正文用户批准及其他待审/待补数量保持原分母。

需求及规则来源：[P4联合设计](<P4 单场计划与持久生成联合详细设计.md>)及[任务](<P4 单场计划与持久生成开发任务.md>)、[领域模型](<轻量领域模型与模块职责.md>)、[一致性边界](<业务协作与一致性边界.md>)的CB-VERSION/DELETE/CLEAN和PF-02/05/08、[技术决策](<技术决策记录.md>)TD-02～07、[实现证明计划](<实现证明计划.md>)。仍采用M-03计划拥有者、M-02资料与删除屏障、M-06审核及固定检索公开能力；不新建通用生成平台。

## 2. 实施契约与取舍

- 原`PlanTransport`的proof开关、回环地址和隔离PG标记保持。新增`BailianPlanTransport`，默认关闭，真实端点必须是同一北京业务空间的专属HTTPS原生query和OpenAI兼容chat地址，禁止重定向、换模或自定义联网工具；Key只在真实通道显式启用并通过配置校验后读取。
- query固定`qwen3.7-text-embedding-flash`、1024维、`text_type=query`；复用document配置必须由M-06核供应商、业务空间、地区、适配修订及完整端点。M-06不按验证profile/布尔参数放行配置；替身声明相同配置契约，但只返回合成向量，不实际访问所声明的供应商地址，也不证明真实索引可用。生成固定`qwen3.8-flash`、JSON、单输出、非流式、`enable_thinking=false`、`max_tokens=4096`，明确提示词修订`plan-json-default8-single-binding-v2`。原工作稿CONFIG/POLICY不重写，真实协议/提示词修订另外纳入窗口冻结摘要，升级不静默续跑旧窗口。
- 首版只覆盖原实际生产契约：**固定单技术条件、一个主题、8题/MEDIUM**。人审通过的多主题参考不因本切片变成已运行通过；真实执行需选其中契约可覆盖的一个合成子集，不批发10例。仍要求资料有`[P4-SYNTHETIC]`标记、正文摘要完全匹配；不开放真实个人资料外发。
- 新V14追加窗口和请求账本，旧V1～V13及manifest不改。`CONTROLLED_TRIAL`独立于`ENGINEERING_ONLY`，M-06仍`runtimeReady=false`，不变为普通训练/面试资格，不创建场次或扣次。受控结果保留原查询与不可变初始修订，本切片不放行修改/确认/P5；修订页能力正确返回只读。
- 单次授权身份固定`p4-20261104-single-window-v1`，数据库主键和唯一workspace禁止换UUID重开窗口、重启清零或替换manifest。窗口绑定本人账号/凭证代次、工作稿和input摘要、合成正文摘要、已批准参考ID及绝对起止时间。配置/输入变化停止，不用最新版替换。

### 2.1 Token、费用与人工核实

不把字符数/UTF-8字节数当模型Token计数。本版采用**人工核实完整wire请求后登记一次发送许可**：服务端先准备原请求摘要；本人查看请求，用与目标模型/实际协议一致的核计方法，登记`inputTokens`上界及`tokenizerEvidence`（方法、版本、记录位置，必须覆盖全部系统提示/知识/资料和chat封装）。摘要变化、依据缺失、query>4000或生成>32000拒绝许可；无法取得可信核计依据就保持未派发。此为显式人工事实输入，系统不声称能自动验证证据真实性；隔离夹具的1000Token许可仅为故障测试数据，不是实际Tokenizer证明。

窗口按已确认费率（人民币/百万Token：embedding输入0.125，生成输入0.8/输出2.7）保守预留：query核实输入上界、生成核实完整输入上界＋4096输出。预留不扣免费额度、失败不退回、不把实际账单硬封顶承诺成5元。回执需有requestId和非负整数usage，query两项Token一致，生成总量等于输入＋输出；输出、输入超过许可或协议矛盾即停。供应商返回usage不能证明核计工具正确或最终计费，真实试验仍要复核。

正常query回执后窗口`WAITING_BILL`，阻止生成，原向量可存储；必须登记**实际费用**与原requestId、核实依据后才能继续原操作。金额未知不填0，免费实际抵扣为0也须有账单依据。已派发预算不退回，超出原预留即永久停止该窗口。已停止窗口仍允许对已知requestId核账，但不会复活调用；资料换版后仍可停止/核账，删除后拒绝新增核计/账单文字。生成结果的接收与账单事实分别保存，不能把参考批准或估价写成实际账单。窗口任何外部失败/未知均停止后续外调，不允许借产品原四次预算追加本窗口调用。

### 2.2 状态、事务和删除

| 步骤 | 持久事实与恢复 |
| --- | --- |
| 首次准备 | 原工作稿进入`WAITING_MANUAL/TRIAL_REQUEST_PREPARATION`，请求`PREPARED`；没有attempt或外调计数 |
| 请求核计许可 | 原摘要对应`APPROVED`，不发HTTP；相同登记重放原事实，冲突拒绝 |
| 派发事务 | 账号→资料根→固定知识根→工作稿→窗口；窗口预留/attempt/产品计次/绑定共同提交，之后才进入发送核实 |
| 单次发送权 | 再核本人凭证、资料、知识、期限、原窗口及原摘要，持久`send_claimed=true`；未知提交不发HTTP，重复不能再次领取 |
| 供应商回执 | 在业务事务外单次HTTP；独立用量登记，正常转`WAITING_BILL`；失败/缺usage/矛盾转`STOPPED`。正常回执不撤销已有停止 |
| 候选/接收 | 沿用生产M-03校验、M-02核对、M-06适用及停用保护；应急停用后迟到结果不接收，已知用量仍留存 |
| 重启 | `INTENT`残留视为可能执行，窗口停止；原成功/候选先查询，既不清零也不猜远端没执行 |
| 删除 | 同一删除屏障停止窗口；PLAN_PRIVATE_GRAPH清理同时清新增input/wire摘要和核计/账单依据文字；仅留身份/数值审计，不留下新正文副本。不外推到供应商留存 |

外调绝不持业务事务；窗口行最后加锁，不在持窗口锁时向别的模块获取新领域锁。串行许可由持久INTENT/一类至多一次/一次性send_claimed共同实现，和现有共享外调许可共存。服务截止仍60秒，query≤10秒、排队20秒；HTTP绝对截止还取窗口结束时间的更早值。停派发与send_claimed的提交是先后边界：已经领取的请求可能已发出，停止/超时不能追溯证明供应商未执行或未收费。

## 3. 配置和操作入口

默认`BAILIAN_PLAN_ENABLED=false`，不改现有`.env`、Docker或运行容器。显式装配时需要以下项；这些是示例，**本轮未设置/执行**：

```properties
BAILIAN_PLAN_ENABLED=true
BAILIAN_EMBEDDING_ENABLED=false
BAILIAN_EMBEDDING_WORKSPACE=<北京业务空间ID>
BAILIAN_EMBEDDING_ENDPOINT=https://<业务空间ID>.cn-beijing.maas.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding
BAILIAN_PLAN_ENDPOINT=https://<业务空间ID>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions
DASHSCOPE_API_KEY=<受控注入，不写记录或日志>
P4_CONTROLLED_TRIAL_MANIFEST=<本机受控JSON文件绝对路径>
```

JDK三个启动属性沿用[适配门禁](<百炼配置接线与向量模型适配：实现与验证记录.md#7-两项p2独立修补与隔离补证2026-11-02>)；关闭proof，确认目标库/schema/最新JAR及迁移授权后才可启动，不能把此示例当开发库迁移许可。真实配置不接受回环端点；故障验证专用profile须同时满足新隔离PG16的名称、环境身份及数据库comment，并仅允许固定假Key和回环HTTP。

manifest仅元数据，不放Key、正文、向量；文件不能为符号链接、最大8192字节。它属于操作者受控配置，本系统不删除操作者任意指定的宿主机配置文件；数据库清理只声明本系统新增私有核计/账单文字及wire摘要已清理，不能宣称宿主机配置/操作人材料或供应商数据都已删除。字段如下（值由实际执行装配产生，不照抄测试ID或Token）：

```json
{
  "authorizationId": "p4-20261104-single-window-v1",
  "ownerId": "<本人账号UUID>",
  "credentialVersion": 1,
  "workspaceId": "<未执行的原工作稿UUID>",
  "inputDigest": "<原工作稿固定输入SHA256>",
  "resumeDigest": "<固定简历正文SHA256>",
  "jdDigest": "<固定JD正文SHA256>",
  "caseId": "REF-P01/v0.1",
  "startsAt": "<绝对起始时间，带时区>",
  "endsAt": "<绝对结束时间，带时区>"
}
```

以上JSON为字段示意，未填空白不构成可运行manifest。工作稿先经原生产API创建，集合/版本/claim等仍属于其固定Input；实际参考与合成正文对应由执行者核实。manifest、核计原文、账单记录均本机受控，不提交仓库。窗口核计/账单接口属于运营事实登记，不能捏造真实供应商记录。

| API | 用途 |
| --- | --- |
| `GET /api/plan-controlled-window` | 本人窗口及原请求摘要、预留、usage/账单状态；默认未配置404 |
| `GET /api/plan-controlled-window/request?kind=QUERY或GENERATE` | 本人完整原wire预览及SHA256；no-store，不在浏览器持久化 |
| `POST /api/plan-controlled-window/approval` | `{kind,wireDigest,inputTokens,tokenizerEvidence}`，需原账号、当前会话、CSRF及`X-Expected-Account-Id`；不是另开调用授权 |
| `POST /api/plan-controlled-window/bill` | `{kind,requestId,billedCny,reference}`，独立登记原实际费用，未知不填零 |
| `POST /api/plan-controlled-window/stop` | 本人永久停止该窗口，不能借重新启用配置复活 |

继续使用原`generation`、`resume`与原操作查询API。顺序：生成受理→query PREPARED→核计许可→恢复原操作→保存query/WAITING_BILL→核实费用→恢复原操作准备生成→生成许可→恢复原操作→原候选/正式结果查询。每个恢复只核原身份，不额外生成query或申请新预算。提交结果未知先GET窗口及原操作；相同许可/账单可按相同参数重放，不改变已知事实。404不单独证明原请求未受理。

本切片只提供受控API，未新增试验页面。原前端完整受控流程、真实Tokenizer核计、真实index/query兼容性、真实召回/引用/计划质量及最终账单均不能由HTTP替身证明。

实际供应商若不能在窗口内提供可核实费用，保持暂停/到期停止，不用Token乘费率或免费额度推定实际账单。该工程闭环不承诺一次真实窗口一定能完成query和生成。没有合格真实document索引时，D=0首版不能运行；需另行落实原已授权D≤4范围内的受控document构建及共享额度接线，不能直接打开现有维护开关。

## 4. 协议依据

2026-11-04读取阿里云官方[文本向量同步API](https://help.aliyun.com/en/model-studio/text-embedding-synchronous-api)及[OpenAI兼容Chat API](https://help.aliyun.com/en/model-studio/qwen-api-via-openai-chat-completions)：核原生query参数、flash usage/响应映射、北京专属地址、JSON模式及直接HTTP的thinking字段。只读取公开协议，没有供应商调用。实际账号权限/费率沿用户确认记录；协议示例不是当前账号实测证据。

## 5. 验证与证据

最终检查使用187项源码/脚本的原始SHA256绑定，命令、完整快照、报告、事件顺序与持久结果索引为`.cache/controlled-wiring-20261104/verification-index.json`。下列最终PG记录均`currentSourceMatches=true`；所有隔离资源和失败报告保留，供应商仅本机故障替身，生产M-01/02/03/06规则/鉴权/仓储/事务不替换。收尾再次只读查GitHub实时main仍`ee2b445`，本地HEAD未改、暂存区0。

| 最终源码验证 | 实际结果 | 证据前缀（均在`.cache/`） |
| --- | --- | --- |
| 受控默认闭环、费用分离、重启及私有清理 | 1/1；Q1/G1，重复查询0新增HTTP；合成账单不是实际账单 | `p4dt01_verify_0bf4cd041a34463f` |
| M-06真实配置规则及错业务空间拒绝 | 1/1 | `p4dt01_verify_f8e1933e25c84674` |
| 非法向量保留用量/停止派发 | 1/1 | `p4dt01_verify_86949bb701354d24` |
| 外部未知，不填零/不追加外调 | 1/1 | `p4dt01_verify_8a9b184c40b14507` |
| 派发已提交确认丢失，重启不发送 | 1/1 | `p4dt01_verify_93af6dd2b3194419` |
| 回执提交确认丢失，已知用量保留 | 1/1 | `p4dt01_verify_e7d3f7f19f2140eb` |
| 应急停用拒收迟到query、保留回执 | 1/1 | `p4dt01_verify_821bc273c5ec4702` |
| 迟到断连不覆盖首次停用原因 | 1/1 | `p4dt01_verify_711f918ce9374cb1` |
| 生成期间删除，FENCED后核私有清理/DELETED原因 | 1/1 | `p4dt01_verify_c4ea83f89a734b0a` |
| 换版后停止/核账不复活窗口 | 1/1 | `p4dt01_verify_86b2883e0bea40c5` |
| 错配置启动门禁、实际JAR跨JVM保留窗口 | 1/1；实际外部调用0 | `p4dt01_verify_1308d74dcb6a4d03`，含`-trial-jar.log/.err` |
| 原Plan生产回归、故障与真实Chrome | 40/40 | `p4dt01_verify_004e5129759b482b`，含`-browser.json/.png`及重启记录 |
| 原Revision全套 | **15/16，整体失败**；15项后端通过，第二窗口加载超时1项 | `p4dt01_verify_a2a051abc05f4b5c`，含`-revision-browser.log`；这次未见前次建连错误，不归并为同一根因 |
| Revision Chrome失败项针对性复验 | **1/1**，真实Chrome完整编辑/CAS/原回执/刷新/账号切换 | `p4dt01_verify_7946db85f77d40e0`，含`-revision-browser.json/.png`；不把原全套报告改成16/16成功 |
| 原历史及真实Chrome回归 | 9/9 | `p4dt01_verify_83d0a1e97dcd402a`，含历史浏览器报告/截图 |
| 最终基础检查 | 76/76，0失败/跳过 | `controlled-wiring-20261104/base-after-review.log`及`base-after-review-reports/` |

文档检查保存`documentation-checks.json`：100个规划ID/已批正文保持，旧V1～V13相对本轮开始原始摘要一致，原三份参考及四份继承统筹/参考文档逐字节不变，检查相对链接/锚点、UTF-8及冲突标记。`git diff --check`通过。无真实供应商、开发库迁移、部署或提交推送；本机配置和当前Docker容器没有更新。

耗时说明：从工作区保护快照起约63分钟；已采集的49次隔离执行阶段时间并集约33分钟（约53%，见索引实时数值），其余含阅读、实现、审阅、文档、隔离准备及两次独立基础检查，未记录的细项不编造比例。额外复跑分别因安全路由遗漏、费用/停止边界补齐、跨JVM摘要、移除验证专用资格分支，以及建连/浏览器故障。前版记录保留，最后一次Chrome只复测失败项，没有为了消除失败再次盲跑整个修订套件；后续优先保留这种最小复验策略。

已执行：新隔离库`p4dt01_verify_5d08d470c92642f3`实际打包JAR启动/health UP及V1～V14迁移，证据`.cache/p4dt01_verify_5d08d470c92642f3.json`和同前缀启动/迁移日志。这是当时源码的启动事实，后续源变更另绑定最终验证，不外推为全部产品通过。

首轮完整受控场景失败库`p4dt01_verify_aa386b75888b40bb`：新增受控API遗漏现有安全路由，返回403，未执行query/生成；补精确认证路由后复验。不关闭Security、不允许匿名，不改测试期望掩盖问题。日志、报告和源码快照保留同前缀。

收尾审阅修正：窗口冻结摘要改为稳定字段排序，避免`Map.of`跨JVM迭代顺序随机导致同一窗口无法重启；补实际打包JAR跨进程启动与错误配置拒绝启动。移除M-06中早期验证专用配置放行，外部替身声明与真实document适配一致的元数据，配置资格也执行生产规则，并补错误业务空间拒绝。每次修正前的报告保留，最终以`verification-index.json`的`currentSourceMatches=true`记录为准，不把前版通过改写为最终源码通过。

另补停用幂等：首次停止原因不被迟到断连/异常覆盖；删除是更强屏障，可将此前原因更新为DELETED，随后保持。删除与生成返回竞争的补证要等原attempt实际FENCED，再核停止原因/私有清理，不只看删除前已有的空候选。

环境失败保留：`p4dt01_verify_a868a5693fc04674`修订套件15项通过、Chrome一项失败；日志显示PG建连`BindException: Address already in use: getsockopt`，浏览器停在DRAFT。事后TIME_WAIT计数20，没有记录故障峰值，**全局端口耗尽仅为资源压力推断，不能写成已测得事实**。夹具此前每条SQL通过DriverManager新建TCP，改用现有Spring JDBC基线选择的连接池并随上下文关闭，仍包裹真实PG Connection注入提交故障；未修改宿主机端口范围/网络设置、不安装依赖。修补后的实际复验另列，原失败不删除或改为通过。

实际入口命令（根目录；每个Trial场景各新库，不在同一窗口重置后连跑多个方法）：

```powershell
& './scripts/Use-LocalTools.ps1'
./scripts/Verify-P4Plan.ps1 -Suite Trial -Cases controlledWorkflowUsesProductionRulesAndNeverReplaysHttp
./scripts/Verify-P4Plan.ps1 -Suite Trial -Cases documentBindingUsesProductionProviderAndWorkspaceRules
# 其他故障场景同样各自执行；完整方法名及实际Maven命令保存在verification-index.json。
./scripts/Verify-P4Plan.ps1 -Suite Plan
./scripts/Verify-P4Plan.ps1 -Suite Revision
./scripts/Verify-P4Plan.ps1 -Suite History
# 基础检查在backend目录用现有Wrapper：./mvnw.cmd -Dmaven.repo.local=../.cache/maven-repository -B -ntp test
```

本轮未跑完整M-06维护/索引/集合及完整P2套件；不能据M-03调用公开能力通过替代这些套件、真实文件/备份恢复或所有PF/VP。只读检查发现部分旧M-06升级夹具的固定计数仍停留V12，需要后续单独核对当前主线；本轮没有运行它们，不能写成已复现失败，也不擅自把它们全部列为本工程阻塞。

## 6. 停止点与下一步

完成工程审阅后，另行装配同空间合格索引、一个已批准参考的契约覆盖子集、实际系统ID/合成正文摘要和一次绝对窗口。核实完整wire的Token及计费依据后，才在已有授权上限内执行P4-DT-05有限真实试验；不重复要求材料审批，也不借本切片自动启动窗口/部署/P5。普通运行、document维护窗口、多主题默认生成、全部人工参考和AI质量仍开放。

## 7. 变更与审阅入口

| 文件/范围 | 作用 |
| --- | --- |
| [BailianPlanTransport](../backend/src/main/java/dev/interviewcoach/plan/BailianPlanTransport.java)、[ControlledPlanWindow](../backend/src/main/java/dev/interviewcoach/plan/ControlledPlanWindow.java) | 真实协议、默认关闭及独立窗口/请求/核计/核账拥有者 |
| [PlanService](../backend/src/main/java/dev/interviewcoach/plan/PlanService.java)、[PlanTransport](../backend/src/main/java/dev/interviewcoach/plan/PlanTransport.java)、[PlanApi](../backend/src/main/java/dev/interviewcoach/plan/PlanApi.java) | 生产受理/派发/接收/回执/原操作/删除，proof隔离及本人操作入口 |
| [PlanEvidenceFacts](../backend/src/main/java/dev/interviewcoach/knowledge/PlanEvidenceFacts.java)、[安全路由](../backend/src/main/java/dev/interviewcoach/identity/LocalSecurityConfiguration.java) | M-06公开配置资格，统一会话/CSRF与角色/所属保护 |
| [V14](../backend/src/main/resources/db/migration/V14__create_controlled_plan_trial.sql)、PlanHistoryService、PlanRevisionService | 新持久窗口/请求，明确受控资格投影及只读结果，不改变旧计划 |
| [ControlledPlanTrialIT](../backend/src/test/java/dev/interviewcoach/identity/ControlledPlanTrialIT.java)、PlanGenerationIT、PlanRevisionIT、ResumeMaterialIT、[验证脚本](../scripts/Verify-P4Plan.ps1) | 实际生产路径、供应商协议故障、追加迁移前提与每场景新隔离库 |
| README、开发计划、P4设计/任务及DT-04材料顶部、本记录 | 当前工程状态/入口；保留历史、人工确认和其他开放项 |

AGENTS、前端代码、配置、旧迁移、旧manifest未修改。前轮9份材料仍保留且未暂存，四份参考/统筹文档相对本轮开始逐字节不变，其余仅加本轮入口或更新当前导航；不得把所有工作区差异自动归为本工程源码。正式PR要按实际暂存范围说明前轮材料的依赖/归属，本轮不替用户提交或混合整理。

建议PR标题：`接通百炼计划生成与query，增加单次受控试验门禁`。

建议描述应说明：默认关闭、D=0/Q≤1/G≤1、固定合成工作稿、V14追加、Token核计许可、持久预留/单次发送权、费用未知暂停、停止及删除迟到保护；填写下方最终实际验证结果，明确真实供应商调用0、没有普通运行/质量放行、没有部署/开发库迁移。前轮材料是否随PR纳入由实际差异决定，不能写成已提交或已获正式人工PR批准。
