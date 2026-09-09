# PhoneBridge 一体化扩充实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 `cad00d0` 基线上完成任务队列、自治审批、可恢复同步、Mote 成长表现和 Web/Android/CI 收口。

**架构：** 服务端以 `WorkspaceStore` 保存真相源，新增小型任务执行器和同步协调逻辑；Android 通过 Room/outbox 镜像协议状态。所有高风险动作经过硬禁止和审批门，Mote 行为由统一 profile/behavior 数据驱动。

**技术栈：** Node.js 内置测试、Node HTTP/WebSocket、Android Kotlin、Room、WorkManager、GitHub Actions。

---

## 文件职责

- 创建 `server/task-runner.js`：可注入的排队、进度、暂停、取消和重试生命周期。
- 修改 `server/workspace-core.js`：任务运行元数据、同步状态、审批租约和审计。
- 修改 `server/index.js`：任务执行、自治审批、同步 API/WS 和 Web 工作台。
- 修改 `server/mote-profiles.js`：关系经验、任务和行为事件。
- 创建/修改 `server/*-expansion.test.js`：先写失败测试，覆盖每个新增边界。
- 修改 `android/app/src/main/java/com/phonebridge/WorkspaceProtocol.kt`、`WorkspaceRepository.kt`、`OutboxSyncWorker.kt`、`MainActivity.kt`、`CompanionView.kt`、`RealityLensView.kt`：同步、任务和 Mote 端一致实现。
- 修改 `android/app/src/test/java/com/phonebridge/*Test.kt`：协议、回退和行为映射测试。
- 创建 `.github/workflows/ci.yml`：Node 测试、Android 单测和静态检查。
- 更新 `README.md`、`HANDOFF.md`：以实际新提交和验证结果为准。

### 任务 1：同步协议与游标恢复

**目标：** 让断线重连能从 `revision` 获取缺失 `workspace.sync_state` 事件，重复事件不重复应用，缺口安全回退快照。

**文件：**
- 修改：`server/workspace-core.js`、`server/index.js`
- 修改：`android/app/src/main/java/com/phonebridge/WorkspaceProtocol.kt`、`WorkspaceRepository.kt`、`OutboxSyncWorker.kt`、`MainActivity.kt`
- 测试：`server/sync-expansion.test.js`、`android/app/src/test/java/com/phonebridge/WorkspaceProtocolTest.kt`

- [ ] **步骤 1：编写失败测试**：断言同步响应含 `fromRevision/toRevision`，事件按 revision 排序；重复 `eventId` 只应用一次；请求 revision 早于保留窗口时返回 `resetRequired=true`。
- [ ] **步骤 2：运行测试确认失败**：运行 `node --test server/sync-expansion.test.js`；预期新断言失败而现有测试保持可加载。
- [ ] **步骤 3：实现最小同步协调器**：增加保留窗口判断、增量响应字段、全量快照回退和 Android 游标 reducer；outbox 只有收到 accepted/duplicate ACK 才移除。
- [ ] **步骤 4：运行验证**：运行 `node --test server/sync-expansion.test.js server/workspace-api.test.js` 和 `:app:testDebugUnitTest`；预期全部通过。
- [ ] **步骤 5：提交**：`git add server android docs && git commit -m "feat: harden workspace sync recovery"`。

### 任务 2：真实任务队列与运行审计

**目标：** 任务动作改变真实执行上下文，而不是只改变存储状态；支持并发上限 1、暂停、取消、重试和恢复。

**文件：**
- 创建：`server/task-runner.js`
- 修改：`server/workspace-core.js`、`server/index.js`
- 测试：`server/task-runner.test.js`、`server/workspace-api.test.js`

- [ ] **步骤 1：编写失败测试**：用注入的 fake runner 创建两个任务，断言第二个排队；暂停阻止下一步、取消触发 abort、一次失败按退避重试、每次转换写入 task audit。
- [ ] **步骤 2：运行测试确认失败**：运行 `node --test server/task-runner.test.js`；预期因 `TaskRunner` 不存在或生命周期未实现而失败。
- [ ] **步骤 3：实现最小执行器**：实现 `enqueue/start/pause/resume/cancel/retry`，为每次运行维护 `runId`、attempt、startedAt、endedAt、abortController 和 progress；把结果写回 `WorkspaceStore` 并广播事件。
- [ ] **步骤 4：接入现有会话任务**：让 `runWorkspaceMessage` 通过执行器提交；取消或暂停必须阻止后续模型调用，并保留结果/错误摘要。
- [ ] **步骤 5：运行验证**：运行 `node --test server/*.test.js`；预期所有服务端测试通过，且任务队列新增测试全部通过。
- [ ] **步骤 6：提交**：`git add server && git commit -m "feat: add resumable task runner"`。

### 任务 3：自治审批、租约和紧急停止

**目标：** 受限工具先进入 `needs_confirmation`，用户批准后只允许一次符合 schema 的调用；过期、次数耗尽和 Emergency Stop 必须拒绝或中止。

**文件：**
- 修改：`server/workspace-core.js`、`server/index.js`
- 测试：`server/autonomy-expansion.test.js`
- 修改：`android/app/src/main/java/com/phonebridge/WorkspaceProtocol.kt`、`MainActivity.kt`、`CompanionView.kt`
- 测试：`android/app/src/test/java/com/phonebridge/WorkspaceProtocolTest.kt`

- [ ] **步骤 1：编写失败测试**：注册 reversible 工具并以未确认方式调用，断言生成 pending approval/Attention；批准 token 只能消费一次；过期、错误参数、硬禁止和急停均拒绝。
- [ ] **步骤 2：运行测试确认失败**：运行 `node --test server/autonomy-expansion.test.js`；预期新审批接口断言失败。
- [ ] **步骤 3：实现审批门**：增加 approval id/token、过期时间、消耗次数和明确拒绝原因；将批准调用绑定到 task/actionRun，Emergency Stop 调用 runner cancel。
- [ ] **步骤 4：接入 Android/Web**：增加待确认摘要、批准/拒绝事件和 ActionRun 状态消费；敏感参数只显示摘要。
- [ ] **步骤 5：运行验证**：运行 `node --test server/*.test.js` 与 Android 单测；预期没有硬禁止绕过路径。
- [ ] **步骤 6：提交**：`git add server android && git commit -m "feat: add autonomy approval leases"`。

### 任务 4：Mote 关系、任务和统一行为表现

**目标：** 互动和任务结果能带来幂等关系成长；四个探索形态在两个 Canvas 视图中都有统一行为提示和基础视觉差异。

**文件：**
- 创建：`server/mote-relationship.js`、`server/mote-quests.js`
- 修改：`server/mote-profiles.js`、`server/index.js`
- 测试：`server/mote-expansion.test.js`
- 修改：`android/app/src/main/java/com/phonebridge/MoteProfile.kt`、`MoteBehaviorEngine.kt`、`CompanionView.kt`、`RealityLensView.kt`、`MoteWidgetProvider.kt`
- 测试：`android/app/src/test/java/com/phonebridge/MoteProfileTest.kt`、`WorkspaceProtocolTest.kt`

- [ ] **步骤 1：编写失败测试**：同一 interaction/eventId 只增加一次关系经验；任务完成/失败产生确定性的 quest/mood/behavior；四个新 profile 有映射和未知值回退。
- [ ] **步骤 2：运行测试确认失败**：运行 `node --test server/mote-expansion.test.js`；预期关系和任务接口断言失败。
- [ ] **步骤 3：实现服务端状态**：持久化 relationship、quests、claimed event ids；新增 `/api/motes/relationship`、`/api/motes/quests`、`/api/motes/quests/:id/claim` 和对应 WS 事件。
- [ ] **步骤 4：实现 Android 消费**：扩展 wire model 和 reducer；让新形态使用橙红火星、青紫棱光、绿色守护、靛蓝扫描等确定性提示，不引入大型资源。
- [ ] **步骤 5：运行验证**：运行完整 Node 测试、Android 单测和 `git diff --check`。
- [ ] **步骤 6：提交**：`git add server android && git commit -m "feat: expand mote relationships and quests"`。

### 任务 5：工作台一致性、CI 和交付文档

**目标：** Web 与 Android 显示同一任务/Attention/Mote 状态，并由 CI 重复执行核心验证。

**文件：**
- 修改：`server/index.js`、`android/app/src/main/java/com/phonebridge/MainActivity.kt`
- 创建：`.github/workflows/ci.yml`
- 修改：`android/app/build.gradle`
- 修改：`README.md`、`HANDOFF.md`

- [ ] **步骤 1：编写失败/契约测试**：增加 API payload fixture 断言任务详情、Attention、Mote quest 和 autonomy approval 在 Web/Android 都有字段；CI 文件必须包含 Node 测试、Android 单测和静态检查。
- [ ] **步骤 2：实现工作台**：Web 增加任务详情、运行审计、Attention、审批和 Mote quest 区域；Android 增加摘要、待确认和最近结果，缺失字段使用安全默认值。
- [ ] **步骤 3：实现 CI**：在 Ubuntu runner 安装 Node/Java，执行 `node --test server/*.test.js`、`./gradlew :app:testDebugUnitTest` 和 `git diff --check`；不运行 ADB、不上传运行时文件。
- [ ] **步骤 4：更新文档**：修正 HANDOFF 的实际提交、测试结果和“手机验证暂缓”边界；README 记录新接口、事件和本地 CI 命令。
- [ ] **步骤 5：运行最终验证**：运行 `node --test server/*.test.js`、`android/gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`、`git diff --check`，并执行变更文件敏感内容扫描。
- [ ] **步骤 6：提交**：`git add .github android server README.md HANDOFF.md && git commit -m "ci: align workspace clients and release checks"`。

## 最终验收

- [ ] 五个批次均有真实代码和测试 diff，不能只修改注释或文档。
- [ ] 服务端完整测试通过；Android 单测和 Debug 构建通过。
- [ ] 任务暂停/取消/重试、受限工具审批、同步缺口恢复、Mote 幂等成长均有自动化测试。
- [ ] `git diff --check` 通过，敏感内容扫描无凭据命中。
- [ ] 工作树干净，功能分支已推送；`main` 未被修改。
- [ ] 明确记录手机实机验证仍未完成。
