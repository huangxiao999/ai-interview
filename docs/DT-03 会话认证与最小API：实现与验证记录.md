# DT-03 会话认证与最小 API：实现与验证记录

版本：v1.0｜日期：2026-10-28｜结论：**DT-03 的后端最小认证闭环已实现并通过本轮适用验证**。DT-04 密码重置、DT-05 产品登录前端、DT-06 全量浏览器恢复案例及私有资源隔离仍待执行；不关闭整个 FS-A、ACC-01、PF/VP 或 MVP 验收。

## 1. 范围与追溯

本任务依据[开发计划](开发计划.md) DT-03、[首片设计](首个业务切片详细设计.md)第 5～8 节，实现已有账号的 `csrf → login → me → logout`。对应 PRD-01、ACC-01-01/03 的适用部分、PG-01/IX-01 的后端前提、FS-C01 与 FS-A01～06/13 的适用案例。无公开注册、默认登录页、账号后台、找回密码、密码重置或私有资料接口。

| 文件 | 作用 |
| --- | --- |
| [本地安全配置](../backend/src/main/java/dev/interviewcoach/identity/LocalSecurityConfiguration.java) | 仅 `local` Web profile 启用表单认证 JSON 响应、CSRF、POST 退出、最小授权边界与开发 Cookie；`bootstrap` 保持原拒绝边界 |
| [认证提供者](../backend/src/main/java/dev/interviewcoach/identity/AccountAuthenticationProvider.java)、[账号仓储](../backend/src/main/java/dev/interviewcoach/identity/JdbcAccountRepository.java) | 登录名精确查找、未知账号哑散列、锁外校验密码、锁下重查账号版本与权限；数据库异常不冒充错误密码 |
| [会话身份核实](../backend/src/main/java/dev/interviewcoach/identity/SessionIdentityFilter.java)、[数据库失败边界](../backend/src/main/java/dev/interviewcoach/identity/SessionDatabaseFailureFilter.java) | 已认证请求重新核实账号代次及权限；Session 或账号存储不可核实时拒绝访问并返回 503 |
| [身份与契约](../backend/src/main/java/dev/interviewcoach/identity/AccountPrincipal.java)、[最小 API](../backend/src/main/java/dev/interviewcoach/identity/AuthApi.java) | Session 使用稳定 UUID principal、当前凭证代次及最小权限；公开响应不含密码散列、代次、Session ID |
| [输入与错误](../backend/src/main/java/dev/interviewcoach/identity/LoginInputFilter.java)、[JSON 响应](../backend/src/main/java/dev/interviewcoach/identity/JsonSecurityResponses.java) | 表单类型及空字段拒绝、统一 JSON 错误和 `no-store`；保留 Spring Security 的 CSRF |
| [隔离验证](../backend/src/test/java/dev/interviewcoach/identity/SessionAuthenticationIT.java)、[PG 执行脚本](../scripts/Verify-SessionAuth.ps1)、[浏览器脚本](../scripts/Verify-SessionBrowser.mjs) | 新建隔离 PG16 库、真实 HTTP/Session JDBC 测试和 Chrome 浏览器 Cookie/CSRF/响应丢失测试 |

登录先在锁外执行密码散列校验，再在同一账号行锁保护下重查散列、凭证代次及权限，避免并发改变后使用旧事实建会话。每个已认证请求重新读取账号事实；凭证代次或权限不符时清身份并使当前会话失效。DT-04 尚未实现真正的密码重置事务与全部旧会话撤销，不能仅凭本轮人工推进版本的测试宣称 FS-C02 已完整通过。

## 2. 实际接口与边界

| 请求 | 本轮结果 |
| --- | --- |
| `GET /api/auth/csrf` | 匿名可访问，返回 `headerName`、`token`；按需建立匿名 Session，`Cache-Control: no-store` |
| `POST /api/auth/login` | `application/x-www-form-urlencoded` 的 `username`、`password`，有效 CSRF；成功 200 最小身份 JSON 并轮换 Session ID；空字段 400、坏凭证 401、缺失/无效 CSRF 403、错误媒体类型 415、账号存储故障 503 |
| `GET /api/auth/me` | 登录且代次/权限有效时 200；未登录、过期、退出或旧代次 401；无法核实时 503 |
| `POST /api/auth/logout` | 有效 CSRF 时 204、使当前 Session 失效；错误 CSRF 403；不撤销其他独立会话 |

其他路径按现有授权配置拒绝，匿名与已认证请求分别表现为 401/403。开发 Cookie 为 `SESSION`、HttpOnly、SameSite=Lax、Path=/、非持久；`local` 仅监听本机 HTTP，因此 Secure=false。未来 HTTPS 环境必须单独设为 Secure=true，本轮没有部署配置或安全承诺。会话空闲期限来自 `spring.session.timeout=30m`，与面试 24 小时期限无关。真实页面、Pinia 状态与路由属于 DT-05。

## 3. 实际运行证据

执行目录为项目根，Windows PowerShell；先加载 `scripts/Use-LocalTools.ps1`。本轮实际使用 Oracle JDK 21.0.12、Spring Boot 4.1.2、Spring Session JDBC 4.1.1、PostgreSQL 16.15、Node 22.23.4、Chrome 155.0.8059.86；版本来源为本机工具、锁定依赖和运行环境，不代替其他机器兼容证明。

| 检查 | 结果及证据 |
| --- | --- |
| `backend/` 下 `./mvnw.cmd '-Dmaven.repo.local=../.cache/maven-repository' -B -ntp test` | 6 项基础测试通过；第一次受限运行因本机 loopback 权限拒绝启动 Tomcat，使用允许本机回环连接的环境复测通过；`backend/target/surefire-reports/` |
| 根目录 `& './scripts/Verify-SessionAuth.ps1'` | 最终独立库 `dt03_verify_1ba8ba8891aa4d88` 的 8 项真实 PG/HTTP 集成测试通过，0 失败/错误/跳过；同次 6 项基础测试通过；`.cache/dt03-test-run.json`、`.cache/dt03_verify_1ba8ba8891aa4d88.log`、`backend/target/failsafe-reports/` |
| 实际浏览器 | 上述集成测试调用本机 Chrome：匿名拒绝、缺 CSRF 拒绝、正确登录、HttpOnly Cookie 对页面脚本不可见、登录后 CSRF 轮换、双标签共享会话；在服务端处理退出后丢弃浏览器收到的退出响应，另一标签 `me` 返回 401。脚本仅向子进程环境传入合成凭证，不写入源码、日志或文档 |
| 会话/账号故障 | 隔离库中验证登录前后 Session ID 轮换、旧 ID 拒绝、真实 Session principal UUID 索引、30 分钟 TTL、到期前有效与到期后 401、旧 Session 迟到保存不复活、第二应用上下文读取持久会话；暂时隐藏账号表或 Session 表均返回 503 且不泄露旧身份；未知权限事实返回 503 |
| 开发库只读复核 | `interview_coach` 的账号 0 行、Session 0 行、Flyway 历史 3 行；本轮未向开发库开通账号或改变 V1/V2 迁移 |
| 脱敏检查 | 最终日志检索合成凭证前缀、`password=`、`SESSION=` 和 CSRF 头值标记均未命中；浏览器进程输出也断言不含本轮随机密码 |

隔离脚本每次新建并保留一份 PG16 测试库，不删除已有库、容器或卷。过程中曾有一次测试夹具把浏览器 Base64 编码的 Session Cookie 直接作为仓储 ID 查询，导致 `dt03_verify_1f048fcd32284dae` 的测试失败；修正测试夹具后在新库连续复测通过。该失败库保留，不将失败记录改写为通过。

## 4. 未覆盖内容及下一步

- DT-04 才实现维护者受控重置及新散列与 `credential_version` 的共同提交，并证明旧会话全部逻辑撤销。本轮只验证手工推进版本后旧会话被拒绝。
- DT-05 才提供 Vue 登录页面与前端状态管理；本轮真实浏览器直接访问后端 API，只证明浏览器 Cookie、CSRF、双标签和响应丢失语义，不代表产品页面完成。
- DT-06 继续验证登录成功响应丢失、身份切换时旧响应、页面返回路径和完整多窗口恢复。退出响应丢失及旧 Session 迟到保存已在本轮覆盖，但不外推为全部 FS-A09/10 或 PF-01 已通过。
- 私有资源尚不存在，因此 ACC-01-02/04、跨用户资料隔离与历史/删除权限必须在对应资源切片验证；知识维护者的身份不自动包含试用权限，本轮未制造测试用私有接口。
- 本轮用隔离表故障证明账号或 Session 存储失效时拒绝访问；整机 PostgreSQL 停机、真实服务网络中断与生产 HTTPS Cookie 仍待环境/后续阶段验证。
- 登录表单请求体资源上限尚未单独做边界与负载验证；当前仅验证媒体类型、空字段及正常合成凭证。后续接入试用入口前核实容器限制与拒绝行为，不把此项写成已通过。

DT-03 按开发计划的最小后端完成条件可进入审阅。上述后续任务和缺口继续保留，不执行 DT-04，也不把这份记录当作产品验收或完整安全证明。
