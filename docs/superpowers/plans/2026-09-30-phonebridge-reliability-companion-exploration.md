# PhoneBridge 可靠性、日常陪伴与探索日志实现计划

> **面向 AI 代理的工作者：** 必须使用 `superpowers:executing-plans` 按任务实施本计划；单会话执行，不启动子 agent。每个任务先补失败测试，再实现并验证。复选框用于跟踪进度。

**目标：** 为隐私删除与离线同步增加不会被旧 outbox 绕过的类别版本；增加无签到压力的日常活动、须经用户确认才转为任务的个人目标，以及只从已结算奖励收据派生的探索日志。Android 与 Web 均可操作，沉浸舞台仍是 Android 默认入口。

**架构：** 服务端 `PrivacyCenter` 是类别 revision 的权威来源；Android Room 持有已应用 revision、迁移决策、例程/目标镜像和待发送事件。现有幂等奖励收据继续作为探索日志唯一结算来源。日常活动、目标和日志使用小型领域模块，通过现有 HTTP/WS、`WorkspaceStore`、Room Repository 与内嵌 Web 工作台接入；不扩展自治工具。

**技术栈：** Node.js 原生测试、现有 HTTP/WS 与运行时持久化；Kotlin、Room、协程/StateFlow、WorkManager；内嵌 HTML/JavaScript；共享 JSON fixtures；现有 GitHub Actions。

---

## 执行约束与基线

- 目标分支：`feature/integrated-enhancement`；计划基线：`fe53adb`。先确认工作树、HEAD 和远端状态；保留任何已有改动，不 reset、不 clean、不覆盖用户文件。
- 一次只做一个批次；各批次完成对应测试与文档后单独 commit、push，再开始下一批。不要直接修改 `main`。
- Android 启动继续进入沉浸舞台；新入口放入现有抽屉/设置层。迁移待确认时只暂停同步，不阻塞本地舞台使用。
- 首轮实现不安装 APK、不授予相机/定位权限。需实机时重新发现已授权 ADB 设备和可达地址，不复用旧无线调试端口；设备不可用则如实留待验收。
- 不生成或使用正式签名材料；未验证加密恢复副本前，正式签名仍为发布阻塞项。APK、备份、运行时数据和密钥不得进入 Git。
- 每次实现前确认实际模块路径与协议现状；下列路径是基于当前仓库核实的主要落点，如实现中发现拆分模块已存在，扩展现有模块，不复制第二套状态源。

## 批次 A：隐私类别 revision 与旧客户端/outbox 恢复

### A1. 先为服务端类别 revision 与迁移编写测试

**涉及文件：** `server/privacy-center.test.js`、`server/privacy-center.js`

- [x] 增加旧 `privacy-audit` v1 存档迁移测试：99 条回执可无歧义迁移并按每个类别中唯一删除 `requestId` 数量重建 revision；恰好 100 条及原始输入超过 100 条（加载后触顶）都产生旧类别逐项确认状态，确认任何旧类别后其 revision 至少为 1；新类别不被误判为历史类别。
- [x] 增加类别 revision 的测试：初始值为 0；overview 暴露各类别 revision/迁移状态；新删除请求为涉及类别分配一次单调递增 revision；相同 `requestId` 重放或 pending 删除恢复不重复递增；进程重建后 revision 和迁移选择仍存在。
- [x] 增加部分删除失败、重试、并发删除、不同类别删除的测试，验证 revision 分配、receipt 和已完成类别一致，不能因历史回执仅保留 100 条而回退版本。
- [x] 运行 `node --test server/privacy-center.test.js`，先确认新增用例失败，再实现。
- [x] 将持久格式升级至版本 2，保存 `categoryRevisions`、`migration` 与既有最近 100 条 receipts。少于上限的旧档案从完整 receipt 历史重建各类别 revision；达到上限时，`migration` 仅要求对旧类别 `memories`、`conversations`、`tasks`、`progress` 逐类确认；其它新类别 revision 从 0 开始。模糊类别在用户确认后 revision 至少为 1，确认前暂停相关同步。
- [x] 在新删除 receipt 首次持久化时原子记录本次各类别的新 revision；重复请求沿用 receipt 中原有版本。`overview()` 返回每类 `revision` 及 `migrationRequired`，并返回总迁移状态；保持已有字段不删改。
- [x] 最近完成历史仍最多展示/保留 100 条；pending/partial 删除 receipt 在完成前不能被新历史挤出，以保证进程重启后的重试可恢复。
- [x] 暴露纯领域方法读取类别 revision、确认指定类别的旧数据决策，以及在决策集合完成前查询同步是否暂停；拒绝未知类别、重复选择冲突或未完成的非法状态。A/B 批次新增类别缺少历史记录时 revision 默认为 0，且不加入旧类别待确认清单。

### A2. 将版本栅栏接入 HTTP/WS 事件接收

**涉及文件：** `server/workspace-core.js`、`server/workspace-core.test.js`、`server/index.js`、`server/enhancement-api.test.js`

- [x] 先增加事件接受测试：revision 缺失时旧客户端在类别 revision 为 0 可继续；类别 revision 大于 0 时缺失版本被拒绝；低于服务端 revision 被拒绝；等于或高于当前版本按协议接受；重复 `eventId` 仍返回幂等结果。覆盖 `workspace.message`、`mote.*`、任务/Attention/ActionRun 的类别映射。
- [x] 扩展 `createEventEnvelope` 保留并规范化可选 `privacyRevisions`；事件分类对个人数据校验、豁免设备状态；未分类的 `workspace.*` 个人变更 fail-closed；聊天关联的任务/Attention/ActionRun 同时校验 `conversations` 与 `tasks`，防止只删除任务后旧事件回放。
- [x] `WorkspaceStore.acceptEvent` 从调用方显式读取 PrivacyCenter revisions（无反向模块依赖），先执行多类别 revision 与迁移门校验，再执行原有时间围栏/eventId/origin-sequence 判重。拒绝使用稳定、无个人数据原因码。
- [x] HTTP `/api/workspace/events` 与 WebSocket 共用业务 ACK；拒绝不写入事件日志/业务状态且不广播成功事件。覆盖 HTTP/WS revision 拒绝与 ACK 原因。
- [x] 删除完成后 `/api/privacy/delete` receipt、`privacy.deleted` WS 事件和 timeline payload 包含本次 `categoryRevisions`；保留 overview 原类别计数/删除历史字段。
- [x] 运行 `node --test server/workspace-core.test.js server/privacy-center.test.js server/enhancement-api.test.js server/workspace-api.test.js` 与 `node --test server/*.test.js`（186/186）；`node --check` 与 `git diff --check` 通过。

### A3. Room v4→v5 与 Android 隐私同步元数据

**涉及文件：**

- `android/app/src/main/java/com/phonebridge/WorkspaceEntities.kt`
- `android/app/src/main/java/com/phonebridge/WorkspaceRepository.kt`
- `android/app/src/main/java/com/phonebridge/WorkspaceProtocol.kt`
- `android/app/src/main/java/com/phonebridge/PrivacyDataPolicy.kt`
- `android/app/src/test/java/com/phonebridge/PrivacyDataPolicyTest.kt`
- `android/app/src/test/java/com/phonebridge/WorkspaceProtocolTest.kt`
- `android/app/src/androidTest/java/com/phonebridge/WorkspaceDatabaseMigrationTest.kt`
- `android/app/build.gradle`、`android/app/schemas/`、`.github/workflows/ci.yml`

- [x] 先为 wire compatibility、category/event 分类、revision 缺失/过期决策、Room 迁移默认值和逐类确认策略增加 JVM 单测。旧事件 JSON 不含新字段时必须继续解析。
- [x] Room 数据库升级至 v5；对 outbox 增加 `privacyCategory`、`privacyRevision`、`quarantined` 字段及安全默认值；新增持久化的 `workspace_privacy_state` 表，用于保存服务端已知类别 revision、迁移状态和用户逐类选择。迁移期间不得丢失已有 outbox 和 workspace 数据。
- [x] 开启 Room schema export 并加入与当前 Room 2.6.1 匹配的 `room-testing` / AndroidX test runner；在 `android/app/src/androidTest` 使用 `MigrationTestHelper` 覆盖 v4→v5 与后续 v5→v6 升级，校验原有 session/message/task/outbox 数据以及新字段安全默认值。更新 `.github/workflows/ci.yml` 使用 Android emulator 跑 `:app:connectedDebugAndroidTest`，不把 JVM 单测冒充真实 SQLite migration 验证。
- [x] `WorkspaceEvent` 增加可选类别/revision 元数据并序列化为服务端约定的 `privacyRevisions`；新建事件使用 Room 已确认的当前类别 revision。旧事件解析保持默认兼容。
- [x] 更新 `PrivacyDataPolicy.categories` 为旧四类加 `routines`、`goals`；探索记录归入 `progress`。按事件类型和关联任务元数据映射类别，不用字符串猜测未知事件；未知个人数据事件暂停发送并可诊断，不默认归入安全类别。
- [x] 扩展 Repository 的事务操作：按类别统计 Room/Preferences/outbox 本地数量；选择清除时在单一 DB transaction 中清除该类缓存与待发事件；选择保留时标记旧 outbox 为 `quarantined`，保留只读查看/导出能力，并确保 `readyOutbox()` 永远不返回隔离事件。
- [x] Room migration 测试验证从 v4 升到 v5 后原表保留、revision 默认为 0、已有事件不被意外发送或删除；后续 v5→v6 测试随批次 B 增加。
- [x] 运行 `.gradlew.bat :app:testDebugUnitTest`（工作目录 `android`）；实际 emulator SQLite migration 仍以推送后的 CI 为验收门。

### A4. 首次升级逐类确认与恢复发送门

**涉及文件：** `android/app/src/main/java/com/phonebridge/MainActivity.kt`、`android/app/src/main/java/com/phonebridge/WorkspaceClient.kt`、`android/app/src/main/java/com/phonebridge/OutboxSyncWorker.kt`、`server/index.js` 中隐私工作台、相应 Kotlin/Node 测试。

- [x] 新增纯逻辑/Repository 测试保证：同步门需完整 privacy overview；模糊迁移待确认期间不允许 outbox 事件发送；逐类处理完成并写入 revision 后才恢复发送；应用重启后隔离事件不会进入 ready outbox。旧事件 revision 不会被首次概览“补盖”成新版本。
- [x] Android 连接恢复流程按顺序执行：取 overview → 更新远端类别 revision → 对 revision 落后或待迁移类别执行本地对账 → 再启动既有唯一 outbox worker。Activity 不得额外启动第二个发送循环。
- [x] 沉浸舞台维持可用；抽屉中的隐私提示逐类展示服务端计数、本机 Room/缓存/待发数量，以及“清除旧数据”与“保留并隔离旧待发事件”两项明确选择。不能自动替用户清除或上传旧数据。
- [x] “保留”路径允许用户只读查看，并通过用户主动输入口令生成本机加密导出；不能编辑后偷偷重放或将 payload 写入日志。用户在本地选择后新建的数据附将确认的 revision，确认前仍保持发送门关闭。选择结果需幂等且跨进程重启恢复。
- [x] Web 隐私中心展示类别 revision/迁移状态和服务端逐类处理进度；服务端不能替代用户对手机本地数据作决定。
- [x] 新增共享 fixture `protocol-fixtures/privacy-overview.json` 与 `protocol-fixtures/privacy-event-revision.json`；Node 和 Kotlin 两端读取同一 fixture 验证字段与旧字段兼容。
- [x] 增加 `POST /api/privacy/migration/resolve`，只接受旧类别和 `clear|keep`；Android 先将本机清理/隔离与选择持久化，再幂等确认服务端迁移选择。进程在两步之间退出后，重连可安全重试；不同决策重放返回冲突。接口不替代本机数据处理。
- [x] 运行 `node --test server/*.test.js` 与 `.gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。检查 `git diff --check` 和密钥/Token 扫描。
- [x] `README.md` 与 `HANDOFF.md` 已记录 revision 语义、迁移确认、加密隔离、验证结果和实机待验收项；Batch A 源码 `4d5b6df`、CI wrapper 修复 `f80dea0` 与 AndroidTest assets 修复 `6a84cbc` 均已推送。最新代码 SHA `6a84cbc67930a95bfb504adcfcccdf18a8b30ddb` 的 GitHub Actions [run 36648683178](https://github.com/blueicx/PhoneBridge/actions/runs/36648683178) attempt 3 全绿，Room emulator migration 7/7。
- [x] 交接证据补录提交并推送后，最新 HEAD 的 Actions 全绿（run `36655906304`），再开始批次 B。

## 批次 B：可选日常活动与用户确认目标板

### B1. 日常活动领域模型与 API

**新增文件：** `server/daily-routines.js`、`server/daily-routines.test.js`

**接入文件：** `server/index.js`、`server/enhancement-api.test.js`

- [x] 先写测试定义三种固定活动：专注计时、无需权限的散步观察、文本睡前回顾。覆盖创建、暂停/继续、跳过、完成、中断、重启恢复、重复 `eventId`、非法状态转换和文本长度上限。
- [x] 实现持久化 `DailyRoutinesStore`，只保存白名单活动记录、事件去重收据和用户主动输入；不创建连续签到、逾期惩罚、强制打卡或默认主动提醒，不自动写入长期记忆。
- [x] 增加接口：`GET /api/routines` 返回活动目录、当前未结束条目及历史分页；`POST /api/routines/:id/events` 接收 `{eventId, action, occurredAt, elapsedSeconds?, reflection?}`，并回传 `privacyRevision`。允许动作仅为 `start|pause|resume|skip|finish|interrupt`，eventId 幂等，校验时序和状态转换。
- [x] 给服务端 `PrivacyCenter` 注册 `routines` 类别 adapter，准确计数、加密导出、删除及类别 revision。Android Room/outbox 镜像归类与本机清理在 B3 实施，不属于本批服务端范围。
- [x] 日常活动 POST 复用 HTTP 入口的 `beginMutation()` 隐私删除互斥门；删除进行中返回可重试 409，revision 栅栏阻止旧事件在删除后重放。集成测试验证在途写、删除等待和新写拒绝。
- [x] 运行 `node --test server/daily-routines.test.js server/privacy-center.test.js server/enhancement-api.test.js`（27/27），并运行 Node 全量测试（202/202）、相关 `node --check` 与 `git diff --check`。

### B2. 目标草案、确认与关联任务

**新增文件：** `server/goal-board.js`、`server/goal-board.test.js`

**接入文件：** `server/index.js`、`server/workspace-core.js`、`server/workspace-core.test.js`、`server/enhancement-api.test.js`

- [x] 先测试目标的创建、编辑、里程碑进度、事件重放、删除级联、任务删除解绑，以及 AI 草案不写持久状态、不创建任务。
- [x] `WorkspaceStore` 是目标、里程碑、eventId receipt 与关联普通任务的唯一持久化权威；扩展其现有 workspace snapshot，而不另造一份会与 task state 分叉的 goal 存储。`goal-board.js` 作为领域/API service。目标与里程碑有稳定 ID/时间/状态；目标数据注册为 `goals` 隐私类别，参加加密导出、全类删除与 revision 保护。
- [x] 增加 `GET /api/goals`、`POST /api/goals`、`PATCH /api/goals/:id`、`DELETE /api/goals/:id`；删除目标时拒绝其关联任务仍在运行的情况，事务级联删除其专属里程碑、普通任务、Attention、ActionRun/audit 关联记录，并更新 `goals` 类别 revision。完整目标任务 ID 枚举不受普通任务列表 200 条投影上限影响。
- [x] 增加 `POST /api/goals/:id/draft`：只在用户显式请求时调用当前选定 provider；输入长度与输出步数设上限，schema 校验失败视为 provider 失败。失败只回退本地规则，不选择其它联网 provider。草案只在响应中返回，不保存、不创建任务。
- [x] 增加 `POST /api/goals/:id/accept`，请求含 `eventId` 和用户编辑后的 steps。先校验目标与全部 steps，再由 `WorkspaceStore` 在同一持久化提交中保存里程碑并创建普通 Workspace tasks；提交失败回滚内存变更，不留半组任务。每个任务增加可选 `metadata.goalId` 与 `metadata.milestoneId`。重复 eventId 返回同一结果，不生成重复任务。
- [x] 删除 `tasks` 隐私类别时保留目标与里程碑文本，把受影响里程碑的 `taskId` 清空并复位为未关联；删除 `goals` 时才级联删目标专属任务。不得扩展任何自动工具调用或自治权限。
- [x] 测试当前 provider 失败时不会调用备用联网 provider；确认前任务数为 0，确认后任务数与步骤数一致；拒绝非法/过量/空步骤；持久化失败时不得留下半组任务。provider 审计只记录 provider ID、耗时、降级原因和结果状态，不记录目标正文、草案或密钥。
- [x] 运行 `node --test server/goal-board.test.js server/workspace-core.test.js server/privacy-center.test.js server/enhancement-api.test.js`（65/65）；Node 全量 221/221、性能预算、相关语法检查、敏感扫描与 `git diff --check` 通过。

### B3. Android 数据镜像与操作状态

**新增文件：** `android/app/src/main/java/com/phonebridge/DailyRoutineModels.kt`、`android/app/src/main/java/com/phonebridge/GoalBoardModels.kt`、对应测试。

**接入文件：** `WorkspaceEntities.kt`、`WorkspaceRepository.kt`、`WorkspaceProtocol.kt`、`WorkspaceClient.kt`、`MainActivity.kt`。

- [x] 先写纯 JVM 测试覆盖 routine 状态转换/中断重启、goal 草案编辑与确认分离、未知字段兼容、revision/eventId 去重和 task-goal 映射。
- [x] Room 升级至 v6：加入 routine entry、goal、milestone 本地镜像表；给本地 task 镜像增加 nullable `goalId`/`milestoneId`。Migration 5→6 用默认值安全迁移 v5 数据，不 destructive migration，并为 MigrationTestHelper 增加 v5→v6 用例。
- [x] Repository 提供读取/更新/删除、事务保存已确认 goal+milestone+tasks、解绑任务引用、清理 routines/goals 隐私分类及 outbox 的明确方法。草案仅保存在当前 UI 内存，不写长期存储。
- [x] 增加 HTTP 请求/响应模型和事件 fixture。routine 操作离线时先本地标注“待同步”；收到业务拒绝显示原因，不把传输成功当作业务成功。
- [x] Android 抽屉中新增“日常”和“目标”入口，使用现有 View/XML/协调风格，不替换沉浸启动。专注计时可暂停/跳过/中断；散步观察不请求定位/相机；睡前回顾为纯文本且默认不进长期记忆。
- [x] 目标 UI 允许建立目标、显式请求草案、编辑/删除建议步骤、逐条确认，然后显示与普通任务绑定的进度。退出草案流程不创建目标任务。
- [x] Room 测试覆盖从 v5 迁移、goal cascade、本地 routine/goals 隐私清理和 pending 状态；GitHub Actions API 34 emulator instrumentation **10/10** 通过。
- [x] 运行 `.gradlew.bat :app:testDebugUnitTest`；扩展验收同时运行 AndroidTest 编译、Lint 和 Debug 构建。本机结果及 GitHub Actions [run 36688324533](https://github.com/blueicx/PhoneBridge/actions/runs/36688324533) attempt 2 均通过。修正首轮 CI 暴露的 migration test table name 后，验证了 SHA `cd4eee96b3e592d6cff662ae0f08e27348279d06`。

### B4. Web 工作台与跨端协议一致性

**接入文件：** `server/index.js` 内嵌 Web UI、`server/enhancement-api.test.js`、`protocol-fixtures/` 与 Kotlin fixture 测试。

- [x] 在现有 Web 工作台增加“日常”卡片和“个人目标”视图，不重做整页或抢占沉浸主视觉；routine UI 完整支持暂停/继续/跳过/完成/中断及失败提示。
- [x] 目标草案按钮显式触发 provider；编辑器确认前不调用接受接口；显示使用的 provider 与本地规则降级原因，但不展示或记录密钥/敏感 prompt。
- [x] 目标任务使用既有 task detail/audit 页面；完成状态回写对应里程碑，不复制第二套任务状态机。
- [x] 更新现有隐私类别清单/选择器为服务端 overview 驱动，确保 `routines` 与 `goals` 可单独选择导出或删除，旧类别 ID 与旧客户端请求继续有效。
- [x] 通过共同 fixtures 验证 Android/Web 共用的 routine action、goal draft/accept、GoalTaskRef 字段；未知扩展字段忽略，缺省状态安全回退。
- [x] API/Web 测试覆盖失败消息、刷新恢复、重复提交和无业务写入的 draft 请求。
- [x] 运行 `node --test server/*.test.js`（225/225）及 `:app:testDebugUnitTest`（165/165）、`:app:lintDebug`、`:app:assembleDebug`；执行敏感字段扫描、性能预算、`node --check server/index.js` 和 `git diff --check`。
- [ ] 更新 `README.md` 与 `HANDOFF.md`，注明本机/服务端分别保存何种记录与无需权限的限制。提交批次 B 并 push；等待最新 GitHub Actions 后进入批次 C。

## 批次 C：从结算收据派生探索日志

### C1. 服务端探索日志投影

**新增文件：** `server/reality-log.js`、`server/reality-log.test.js`

**接入文件：** `server/index.js`、`server/enhancement-api.test.js`、`server/privacy-center.test.js`

- [ ] 先测试只返回已确认收据、按 `(occurredAt,eventId)` 稳定倒序、重复收据只一条、游标分页无重无漏、隐私删除后为空，以及输出不含图像/精确坐标/连续轨迹/自由文本。
- [ ] 新增 `buildRealityLog({growthStore, realityEngine, moteProfiles})` 纯投影；只读取已有 Reality/MoteGrowth receipt，不写第二奖励账本、不调用领奖逻辑。条目字段限定为 `eventId,status,occurredAt,coarseRegion,clueType,moteId,observation,reward`。
- [ ] 新增 `GET /api/reality/log?cursor=<opaque-keyset>&limit=50`，验证 limit 上限和不透明 keyset `(occurredAt,eventId)`；未知或畸形 cursor 返回 400，不退化成首屏无限读取。
- [ ] 使用当前 Mote profile 与 clueType 生成固定观察提示；不将模型生成自由文本、原始图像或精确位置写入日志。
- [ ] 探索日志归入 `progress`：沿用既有加密导出与删除 adapter；确保进度删除同步清理对应日志投影来源，日志不可独立发奖。
- [ ] 运行 `node --test server/reality-log.test.js server/reality-engine.test.js server/reality-coordinator.test.js server/privacy-center.test.js server/enhancement-api.test.js`。

### C2. Android 确认/待同步日志合并

**新增文件：** `android/app/src/main/java/com/phonebridge/ExplorationLog.kt`、`android/app/src/test/java/com/phonebridge/ExplorationLogTest.kt`。

**接入文件：** `WorkspaceClient.kt`、`WorkspaceRepository.kt`、`WorkspaceProtocol.kt`、`MainActivity.kt`。

- [ ] 先写测试：离线 outbox 线索显示为待同步且不显示已领奖；ACK accepted/duplicate 后用服务端 receipt 替换同 `eventId` 待同步项；business rejection 显示原因且不创建已确认奖励；分页重叠按 eventId 去重；revision 变化后按 progress 类别清理。
- [ ] Android 日志 repository 合并两种来源：服务端分页已确认 log 与本地尚未确认的 exploration outbox。服务端 receipt 优先；待同步项只标明线索类型、创建时间及“奖励待确认”，不得推算或展示奖励到账。
- [ ] 单条日志详情允许查看已有 receipt 并跳转到 Reality 入口；遭遇已过期时只能查看结果，不重启遭遇、不二次领奖。
- [ ] 网络恢复继续走唯一 Workspace outbox worker；不能为日志新增第二个同步 worker。新服务端页面请求使用游标并缓存已有页。
- [ ] 添加共享 `protocol-fixtures/reality-log.json`，覆盖 confirmed/pending 两种 view-state、粗区域和奖励 receipt 的稳定字段；Node/Kotlin 均验证 privacy allowlist。
- [ ] 运行 `.\gradlew.bat :app:testDebugUnitTest`。

### C3. Web / Android 日志入口、隐私回归与交付

**接入文件：** `server/index.js` 内嵌 Web UI、`MainActivity.kt` 抽屉 UI、共享 fixtures 与相关测试。

- [ ] Web 工作台提供按时间分页的探索记录和 receipt 详情；仅确认收据显示真实奖励，日志行可跳至相关 Reality 记录；删除 `progress` 后刷新结果为空。
- [ ] Android 抽屉提供同一日志字段与状态文案：已确认、待同步、被拒绝。无网/权限拒绝不隐藏日志入口，也不伪造粗区域或实地验证。
- [ ] 回归隐私删除顺序：清除 `progress` 后服务端 Reality、growth、Mote 成长原始 receipt 同时不可被日志接口重新投影；客户端对应缓存/outbox 按 revision 清理或隔离；`routines`、`goals` 独立删除不影响 exploration log。
- [ ] 回归目标删除、任务类别删除、routine 中断、provider 本地降级、旧客户端 revision 缺失、重复线索 eventId；确保无重复奖励、无静默重放。
- [ ] 更新 `README.md` 与 `HANDOFF.md`，分栏报告自动验证、当前实机证据、未验收设备项、签名发布阻塞及复现命令。提交批次 C 并 push；不把 CI 结果写成实机结果。

## 最终验收清单

- [ ] 服务端：`node --test server/*.test.js` 全量通过；特别确认隐私 99/100/101 迁移、revision 重启、并发删除、旧客户端/陈旧 outbox 拒绝、目标任务事务、草案无副作用、活动 eventId 幂等、探索日志 receipt 幂等/分页。
- [ ] Android：在 `android` 目录运行 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 全部通过；CI emulator 上 `:app:connectedDebugAndroidTest` 的 Room v4→v5→v6 升级测试通过；不得使用 destructive fallback。
- [ ] 协议：Node、Kotlin、Web 都读取同一 privacy/routine/goal/reality-log fixtures；`git diff --check` 通过。
- [ ] 隐私/供应链：扫描源码、文档和变更文件，确认无 token、provider key、个人备份、日志、原图、精确位置；APK 不纳入源码提交。
- [ ] 性能/兼容：运行仓库 CI 已有性能预算和发布门禁，不新增未经测量的性能声明；GitHub Actions 在批次 C 最新 commit 上成功。
- [ ] 实机：重新检查无线 ADB 身份、电量、温度和相机/位置权限状态。授权后按顺序验收：沉浸启动、旧存档逐类迁移/恢复、专注暂停中断、无权限散步/回顾、目标草案确认后才建任务、三类线索与离线恢复、日志 receipt、隐私分类删除。未执行/设备不满足条件的项标为“待验收”。不得静默授予权限。
- [ ] 发布：Debug APK 仅作为构建产物校验；正式签名在独立加密恢复副本实际验证前维持阻塞。最终交接分别记录源码 commit、Actions URL/状态、APK SHA-256（若构建）与实机证据位置。

## 建议执行命令

在每批开始与结束时，先在仓库根目录检查：

```powershell
git status --short --branch
git log -1 --oneline
git diff --check
```

Node 测试：

```powershell
node --test server/*.test.js
```

Android 测试、Lint 与构建（在 `android` 目录）：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

每批 push 后，通过 `gh run list --branch feature/integrated-enhancement --limit 5` 确认该批次最新 commit 的 Actions；只有成功才继续下一批。最终交付前重新确认工作树与远端分支指向，不能把旧 run 或其他 commit 的绿灯算作本计划验收。
