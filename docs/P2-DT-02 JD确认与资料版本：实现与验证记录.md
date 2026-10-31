# P2-DT-02 JD确认与资料版本：实现与验证记录

日期：2026-10-31｜状态：本任务代码与本地验证完成，待提交、CI 和独立审查；P2 阶段未验收。

当前审查状态：原切片已提交 PR #12（`d609b60`），下文最初“待提交”属于当时状态；第 7 节记录本地 P2 审查修复，尚未提交、推送或接受最新提交的复审。

依据：[开发任务](<P2 资料与版本开发任务.md>)、[详细设计](<P2 资料与版本详细设计.md>)、[前置记录](<P2-DT-01 粘贴简历私有闭环：实现与验证记录.md>)。

## 1. 基线与实施边界

- 本轮开始工作区干净。本地 Git 的代理 `127.0.0.1:10809` 不可用，`git ls-remote` 未成功；随后通过 GitHub 连接器实际读取远端 `main` 与 PR #11，确认主线 SHA 为 `931a622db3ba7cf79de62d92ba0464af4b0efde3`、PR #11 已于 2026-10-30 合并，前置修复已包含于本地。
- 独立分支为 `codex/p2-dt-02-jd-versions`。没有自动提交、推送或创建 PR。
- M-02 新增 JD 确认/追加及两类资料的历史入口；复用已有简历追加、M-01 `AccountWriteGuard`、会话、CSRF、归属和删除根。
- 不实现 PDF/DOCX、原文件、计划、面试、评分、额度、RAG、联网或完整 P2 页面。没有新增依赖或变更技术版本。

## 2. 实现及固定契约

| 位置 | 职责 |
| --- | --- |
| `backend/src/main/resources/db/migration/V4__create_jd_versions.sql` | JD 不可变版本；同根版本号/操作唯一；允许 `JD_VERSION` 清理凭据。V1～V3 未修改。 |
| `backend/src/main/java/dev/interviewcoach/material/ResumeText.java` | 共用换行/首尾空白规范化及码点统计，分别应用简历 100～20,000、JD 50～10,000 边界。 |
| `ResumeService.java`、`ResumeRepository.java` | JD 追加、按原操作查原版本、两类版本列表与详情、JD 删除影响及真实数据库清理。 |
| `ResumeApi.java` | 新增契约转换，沿用既有 JSON 错误及身份边界。 |
| `frontend/src/api/materials.ts` | 版本查询、统一身份预检、固定操作确认与结果未知核实；删除影响补 JD 数量。 |
| `frontend/src/stores/materials.ts`、`frontend/src/views/MaterialVersionsPanel.vue` | 资料版本状态、草稿和未决操作统一放 Pinia；最小历史查看与编辑确认面板。 |
| `WorkspacePage.vue`、`base.css` | 接入版本面板、稳定版本 ID、真实删除范围及必要布局。 |
| `ResumeMaterialIT.java`、`scripts/Verify-P2Resume.ps1`、`scripts/Verify-P2ResumeBrowser.mjs` | 扩展已有隔离验证，未另建重复测试框架。 |

以 `/api/materials/resumes/{resumeId}` 为根，新增如下接口：

| 方法/路径 | 结果 |
| --- | --- |
| `GET /versions` | 简历版本摘要，按版本号倒序。 |
| `POST /jd-versions` | 确认 JD 首版或追加新版，返回 201 和接受的版本。 |
| `GET /jd-versions` | JD 版本摘要，按版本号倒序；本人有效根暂无 JD 时返回空列表。 |
| `GET /jd-versions/{versionId}` | 指定 JD 的固定正文与身份。 |
| `GET /jd-operations/{operationId}` | 原 JD 确认操作绑定的版本，后续新增不改变该结果。 |

复用原有 `POST /versions`、`GET /versions/{versionId}`、`GET /operations/{operationId}` 简历追加/查询能力。版本响应保持 `resumeId`、`versionId`、`versionNo`、`body`（详情）、`codepointCount`、`confirmedAt`；路径区分简历与 JD，两类版本分别计数。操作身份作用域为资料根和资料种类；后续计划须显式保存两种版本 ID，不使用“最新版本”作为历史引用。

写请求沿用 `X-Expected-Account-Id`、`X-Operation-Id`、CSRF 和 JSON `{body}`。错误沿用：400 `INVALID_INPUT`，401 `AUTHENTICATION_REQUIRED`，403 现有权限/CSRF 错误，404 `RESOURCE_UNAVAILABLE`，409 `OPERATION_CONFLICT`/`ACCOUNT_CHANGED`，503 `RESULT_UNKNOWN`。`GET /deletion-impact` 保留 `versionCount`，新增 `jdVersionCount`，不展示尚不存在的文件或面试数量。

## 3. 一致性与恢复

1. JD 写入在短事务中依次核实 M-01 账号行锁下的凭证代次/权限、预期账号、M-02 根归属和删除屏障，再返回原操作结果或追加版本。序号分配、正文与操作身份共同提交。
2. 版本正文不原位更新。历史查询同时限定账号、根、种类及版本/操作身份，不能用另一个根或最新版本替代。
3. 删除在原短事务内纳入当前简历和 JD 全部版本清理凭据，物理清除两类正文并提交不可逆屏障；任一清理凭据写入失败则共同回滚。屏障后的重放、旧版查询和新写返回不可访问。
4. 版本面板与草稿属于 Pinia。身份核实期间隐藏，同账号恢复保留；确认匿名/切换账号清理。未决版本保存固定 `resumeId/kind/body/operationId`，重试预检失败不丢原操作；当前结果未核实期间不切换编辑目标。
5. 列表请求序号和资料账号代次拒绝旧快照；版本读取还核对当前根。写入开始即使在途旧列表失效，成功后再读取真实列表。页面按钮不代替服务端锁、唯一性和权限。

## 4. 实际验证

环境沿用 Oracle JDK 21.0.12、Node 22.23.4、Boot 4.1.2、PostgreSQL 16.15 和真实 Chrome；未调用外部模型。启动了原先已停止的本项目 PG16 容器，保留其既有卷。

| 检查 | 命令/证据 | 实际结果 |
| --- | --- | --- |
| 前端静态、格式、类型与构建 | `frontend/`：`npm.cmd run check` | 通过；版本面板已进入真实构建。 |
| 后端基础测试 | 根目录加载工具脚本，`backend/`：`./mvnw.cmd '-Dmaven.repo.local=../.cache/maven-repository' -B -ntp test`；[日志](../.cache/p2-dt02-basic.log) | 6 项通过，0 失败/跳过；包含 bootstrap 隔离边界。 |
| 真实 PG16/HTTP/Chrome | 根目录：`& './scripts/Verify-P2Resume.ps1'`；[清单](../.cache/p2dt01_verify_b746aa21a1194dc4.json)、[日志](../.cache/p2dt01_verify_b746aa21a1194dc4.log) | 新建隔离库 `p2dt01_verify_b746aa21a1194dc4`，15 项通过，0 失败/错误/跳过。保留原 `p2dt01_verify_*` 前缀以兼容既有隔离守卫。 |
| 迁移升级与重复执行 | 上述集成检查的初始化夹具 | 先迁移到 V3 并插入合成旧版，再执行 V4；旧正文/ID 保留，重复迁移为 0，Flyway validate 通过。应用真实 local 上下文启动，无重复建表。 |
| 浏览器脚本与差异卫生 | 根目录：`node --check scripts/Verify-P2ResumeBrowser.mjs`、`git diff --check` | 通过；新增文档和链接另做实际存在性检查。 |

本轮新增 6 个数据库/HTTP 测试方法，并扩展已有 Chrome 方法；原 9 个方法随同运行，总计 15 个。浏览器中的多个场景不重复算成独立 JUnit 方法。

| 场景 | 可观察结果 | 来源/覆盖边界 |
| --- | --- | --- |
| JD 输入 | 49/10,001/空白/null 拒绝，50/10,000 接受；48 个补充字符加规范化换行和一个字母统计为 50，原文无截断 | ACC-03-02/03、ACC-02-07；V-P2-02、PF-08 资料部分 |
| 原版、顺序和幂等 | JD V1/V2 按倒序列出；重放/查询原操作仍得 V1 全部事实；改正文重用 ID 返回 409；同操作并发只建一版，不同操作并发得到连续版本号 | ACC-02-05/03-01/04/09-02 的资料部分；CB-VERSION、TD-03、PF-02 |
| 权限、归属和安全凭据 | 匿名 401、B 访问 A 为 404、仅维护者 403；错根版本不可见；无 CSRF 的 JD 写入 403；预期账号不一致 409，未新增 JD | ACC-01-02/09-04；V-P2-01、PF-01 资料部分 |
| 撤销后的迟到写 | 用账号锁固定“过滤器通过→重置排队→旧 JD 写排队→放锁”；重置提交后旧请求 401，JD 数量为 0 | FS-C02、CB-VERSION 与身份边界；原简历创建/追加/删除回归仍通过 |
| 删除共同回滚 | 在 JD 清理凭据 INSERT 注入异常，503 后根仍 ACTIVE，简历/JD 正文均保留，无删除请求；移除故障重试后两类正文清除、凭据齐全、重复删除返回原结果，B 资料仍可访问 | ACC-10 资料部分；CB-DELETE/CLEAN、PF-05 |
| 删除竞争两种顺序 | 账号行锁排队控制：JD 先被接受则纳入清理，删除先提交则 JD 返回 404；最终均无可访问 JD | V-P2-05、CB-DELETE/CLEAN、PF-05 |
| 真实页面与恢复 | 登录→保存简历→未知 JD V1（服务端已提交但回包/查询失败）→重试身份预检失败→保留原标识→同操作重试仅 V1→JD V2/简历 V2→读取 V1→重登查看→删除两类版本 | PG-02/08 最小入口；ACC-03/09 资料部分；PF-01/02 |
| 既有审查回归 | 同账号切窗保留草稿/未决保存、CSRF 更新、账号切换阻止旧正文写入、旧列表响应晚于保存/删除、重置后旧写均继续执行 | 前置记录第 4～8 节；本轮未替代完整 FS-A/PF/VP |

## 5. 失败历史与环境影响

- 首次普通权限的基础测试因回环连接被拒绝而无法启动 Tomcat；允许本机回环后重跑 6/6。第一次隔离脚本因项目 PG16 容器处于停止状态而在创建测试库前结束；启动原容器后继续。
- 隔离库 `p2dt01_verify_d048ca05d3704ce1`：新增泛型断言不匹配，且浏览器夹具过早点击禁用按钮；两处修正后继续，未改业务预期。
- 隔离库 `p2dt01_verify_a01784bc3fa64f99`：边界夹具误把 24 个 emoji 当作 48 个码点；实际规范化为 26，服务端正确拒绝。修正为 48 emoji + 换行 + 字母的独立 50 码点预期。
- 隔离库 `p2dt01_verify_b32c3d0e855046c0`：数据库检查通过，Chrome 夹具刷新后过早匹配旧页面；增加新页面标记等待后从新库重跑，最终 15/15。失败库与日志均保留。
- 开发库只读核查为 `interview_coach`、SQL 迁移 V1/V2、账号 2 条，尚无简历/JD 表；[脱敏审计](../.cache/p2-dt02-development-audit.log)。首次审计因查询尚不存在的简历表而退出，随后改为 `to_regclass` 检查；均为只读事务。没有向开发库执行 V3/V4 或注入故障。
- 新建隔离库、忽略目录日志和 Chrome 临时配置属于本轮验证产物；未创建产品私有文件，没有文件介质清理证明。既有数据卷、真实账号与 `.env` 保留。

## 6. 完成判断与后续

P2-DT-02 的本人 JD 确认、两类版本追加/历史读取、稳定身份、幂等、权限、当前数据删除及最小网页闭环已具备代码和上述本地证据。仍需本任务 PR 的最新 CI 与独立审查；P2-DT-03 应在其合并后从新主线开始。

ACC-02-05、ACC-03-01/04、ACC-09-02 只证明资料版本部分，未证明计划或历史面试引用；SC-10 配置旧场继续与全套 PF/VP 均未关闭。岗位语义、矛盾、低匹配及乱码/纯重复纠正按 P4 的计划准备规则继续验证，本轮长度接受不表示岗位合规。文件与解析、完整页面、24 小时文件清理和备份仍属于后续任务。

## 7. PR #12：删除其他简历保留未决版本（2026-10-31）

Codex Review 指出：同账号先打开简历 B 的删除确认，再打开简历 A 保存版本；若 A 已在服务端保存但回包与原结果查询失败，随后确认删除 B 会无条件清空 A 的选中内容、版本草稿和原操作标识。`WorkspacePage.confirmDelete()` 和 `verifyDelete()` 都存在此清理路径。

修复仅调整这两个删除完成分支：选中详情的根 ID 与删除目标相同才清除详情；版本状态的根 ID 与删除目标相同才调用 `selectVersionRoot(null)`。删除 B 仍核实 B 的服务端结果并刷新列表，不改变 A 的未决正文、操作身份及核实/重试入口。删除当前根时仍执行既有清理。

真实 Chrome 回归在同账号两份简历之间按以下顺序执行，未替换真实 PostgreSQL 或删除接口：

1. B 已保存且处于待删除确认；转为查看 A。
2. A 的新 JD 版本实际接受成功，随后模拟响应正文与原操作查询失败，使页面保留原未决操作。
3. 分别执行：直接确认删除 B；以及 B 的删除已提交但回包/查询失败，再点击“核实删除状态”。
4. 两种删除完成后均断言 A 的 JD 草稿及核实/重试按钮仍在，没有额外的 A 保存请求。
5. 直接删除场景实际点击 A 的“核实版本保存结果”，不发新 POST；核实删除场景实际沿用 A 的原操作重试，POST 携带同一操作 ID。两者均只新增一个版本，返回正文与原未决正文相同。

旧代码复现：隔离库 `p2dt01_verify_f5138072ee16438c` 的 15 项中，浏览器检查在删除 B 后取得 A 草稿为 `undefined` 而失败；[失败日志](../.cache/p2dt01_verify_f5138072ee16438c.log)。修复后先在 `p2dt01_verify_0fa04fb592f14656` 通过两种删除路径，再补充分别实际核实/重试的断言。

最终项目根执行 `& './scripts/Verify-P2Resume.ps1'`：新隔离库 `p2dt01_verify_869872037d03459b`，15 项真实 PG16/HTTP/Chrome 检查通过，0 失败、0 错误、0 跳过；[清单](../.cache/p2dt01_verify_869872037d03459b.json)、[通过日志](../.cache/p2dt01_verify_869872037d03459b.log)。新场景扩展已有浏览器方法，不新增 JUnit 方法计数。`frontend/` 执行 `npm.cmd run check` 通过；项目根 `node --check scripts/Verify-P2ResumeBrowser.mjs`、`git diff --check` 通过。

本次仅修改前端删除完成状态、浏览器回归及本记录，未修改后端、迁移、开发库或真实资料。此前基础测试结果保留，本次未重新运行独立的 6 项基础测试。对应 CB-VERSION/DELETE、PF-02 的原操作恢复及 ACC-03/10 当前资料部分，不扩写为完整 P2 或 MVP 验收通过。
