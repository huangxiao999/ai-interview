# P3-DT-05 Java21并发知识来源与审核草稿

版本：v0.2（确认登记）｜整理日期：2026-11-01｜更新日期：2026-11-03｜状态：用户本轮明确确认“四组 Java21 知识没问题”，登记为四组技术内容确认；许可/使用范围及系统版本批准仍未完成。未入库或构建索引，新参考案例另待人工确认。

确认依据：2026-11-03本聊天用户原话，不代填手写签名、专家资质或逐项许可结论；已有统筹表“已审核”按此内容范围理解。第2～5节技术正文保持原v0.1内容，v0.2仅补确认登记；[首批6例完整正文](<P3-DT-05 Java21并发首批完整参考案例.md>)是之后新整理材料，不在本次知识内容确认范围内。

关联：[统筹记录](<P3-DT-05 人工参考与百炼接入核实记录.md>)、[P3任务](<P3 持久操作与审核知识开发任务.md>)、[P3设计](<P3 持久操作与审核知识详细设计.md>)。以下是AI根据公开规范整理的转述与面试核对建议，不是完整规范译文、许可确认或模型评分标准；不扩大原100例数量。

## 1. 来源登记

下列官方页面已于2026-11-01只读查阅；版本统一为Java SE21。网页可读不代表允许整页复制入库，具体摘录/内部使用许可及必要通知由维护者另核，不预填许可通过。

| 草稿资料ID/修订 | 来源与精确定位 | 建议关联案例 | 人工核实人/日期/结论 |
| --- | --- | --- | --- |
| K-JC01/v0.1 | [JLS21 §17.4.5 happens-before](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html#jls-17.4.5)、[§17.4.4同步顺序](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html#jls-17.4.4) | REF-S05-A～E，检索版本/条件对照 |  |
| K-JC02/v0.1 | [locks包概览](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/package-summary.html)、[Lock：Memory Synchronization及lock/tryLock/lockInterruptibly](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/Lock.html) | REF-S06-A～E |  |
| K-JC03/v0.1 | [atomic包概览](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/package-summary.html)、[AtomicInteger：compareAndSet/updateAndGet/getAndIncrement](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicInteger.html) | REF-S05/S06的回答核对材料，不新增案例 |  |
| K-JC04/v0.1 | [ThreadPoolExecutor：Queuing及Rejected tasks](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)、[ExecutorService：shutdown/shutdownNow/awaitTermination](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html)、[Future：cancel/get(timeout)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html) | REF-S07-A～E，REF-S12的本地/外部未知辨析 |  |

本文件修订不是系统知识版本ID。后续实际入库时，对所用原文/合法摘录与条件生成固定版本和摘要；系统版本ID、审核记录、许可依据、摘要值均待填写，不用页面URL或本文件名冒充原文摘要。

## 2. K-JC01：JMM与happens-before

草稿核对点：happens-before是内存模型中的可见性/顺序约束，不是以墙上时钟判先后。需能识别同线程程序顺序、同一监视器的解锁到后续加锁、volatile写到后续读，以及传递关系。相关定义见[JLS21第17章](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html)。

建议问题：两个线程使用一个共享标记传递准备完成状态，应怎样说明发布与读取的关系？

人工检查方向：要求回答指明具体共享对象、同步方式和前后动作；只说“先运行的线程一定被另一个看到”不能代替关系证明。不要把一次运行看到某结果当成规范保证。此处不预填四维分数；关于复合更新的原子性另结合K-JC03核对。

适用条件：讨论Java21语言内存语义；不据此推断HTTP对端停止、PG事务提交成功或跨进程幂等已经成立。

## 3. K-JC02：锁与临界区

草稿核对点：Lock提供显式加解锁能力；成功加解锁具有文档规定的内存同步效果。使用时区分普通获取、可中断获取和尝试获取。释放应与实际成功获取配对，按[Lock文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/Lock.html)的约束组织清理路径。

建议问题：用显式锁保护一个本地共享状态时，异常退出和未取得锁的分支分别如何处理？

人工检查方向：核锁对象是否一致、保护范围是否覆盖规则、获取失败是否仍误执行临界区；不能因某类型可重入便推断不存在锁顺序问题。锁模型以[locks包](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/package-summary.html)及具体实现契约为准，不把某个实现的公平性当所有Lock的共同保证。

项目关联仅作边界说明：Java锁不替代已确认的账号→知识根数据库锁和同PG事务，也不授权在业务事务中等待外调。该句依据项目TD-02，不是Java API对数据库的承诺。

## 4. K-JC03：原子类与复合业务规则

草稿核对点：[atomic包](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/package-summary.html)提供针对变量的原子操作能力。区分一次原子方法与多步骤业务组合；某个值使用原子类，不代表其与其他值之间的约束自然原子完成。

核对具体方法时，[AtomicInteger文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicInteger.html)说明更新函数可能因竞争重新应用，因此不能把不可重复的副作用塞进更新函数。

建议问题：使用AtomicInteger记剩余名额，“读取判断后再减一”能否直接认为已经实现完整并发准入？应核哪些前提？

人工检查方向：应定位读/判断/写是否是一个受保护决定；不要以“用了CAS”替代对整个规则的解释。该问题的具体实现方案、允许结果、错误回答和参考分由评审者补充，不在草稿中用泛化答案取代实际条件。

## 5. K-JC04：线程池、等待超时与取消

草稿核对点：[ThreadPoolExecutor文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)分别讨论工作线程、队列和拒绝策略。评审配置应结合这三者，不能只看线程数便断言等待队列有界。

[ExecutorService](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html)区分停止接收新任务、尝试停止在途任务和等待终止；shutdownNow不是任务已停止的保证。[Future](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html)的限时get描述等待结果的期限，cancel描述取消尝试；不能把本地等待超时直接表述成任务从未执行或对端未收费。

建议问题：调用Future.get超时后，怎样分别表达本地等待状态、执行任务状态及可能的远端副作用？

人工检查方向：核是否区分排队和执行、取消与终止、业务截止与网络等待；必要时查询原结果并保留未知。涉及供应商费用、代次和预算的结论仍须项目TD-04/06及供应商证据，不能由Java API单独证明。

## 6. 人工审核及入库前登记

| 草稿ID | 许可/使用范围证据 | 逐结论来源支持核对 | 修正与争议 | 审核人/日期/结论 | 系统版本/审核记录 |
| --- | --- | --- | --- | --- | --- |
| K-JC01 |  |  |  |  |  |
| K-JC02 |  |  |  |  |  |
| K-JC03 |  |  |  |  |  |
| K-JC04 |  |  |  |  |  |

2026-11-01初稿没有人工签字或参考分；2026-11-03用户已确认四组技术内容，但许可和系统批准仍待登记。首批案例见前述链接，参考分/引用标注须另审，不直接作为已批准运行时评分依据。通过现有M-06维护能力提交和批准固定版本后方可按后续集合门禁使用；本文件不会触发入库或真实embedding调用。
