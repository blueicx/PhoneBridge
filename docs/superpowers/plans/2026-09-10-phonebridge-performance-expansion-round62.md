# PhoneBridge 性能与功能扩充 Round 62 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在保持单用户、单 PhoneBridge 节点和旧协议兼容的前提下，降低首屏与同步开销，并扩充任务自治、Mote 关系和可观测性。

**架构：** 服务端以 workspace revision 为缓存与增量同步边界，Web 以事件队列和分区 DOM 更新替代重复全量渲染，Android 以后台解析/reducer 和 revision/eventId 去重保持主线程轻量。任务、自治和 Mote 继续复用现有 WorkspaceStore、TaskRunner、ActionRun、MoteRelationshipStore 和 MoteQuestStore，不引入第二套状态源。

**技术栈：** Node.js 原生 HTTP/WebSocket、Node `node:test`、Android Kotlin/JVM tests、WorkManager、GitHub Actions、Canvas 现有渲染体系。

---

## 文件职责锁定

- 修改：`server/index.js`，状态投影、快照缓存、广播合并、内嵌 Web 指挥中心。
- 修改：`server/workspace-core.js`，revision 事件和合并持久化。
- 修改：`server/task-runner.js`，任务运行指标和状态事件。
- 修改：`server/mote-expansion.js`、`server/mote-behavior.js`，关系/任务/行为扩展。
- 创建：`server/performance.test.js`，服务端状态 payload、缓存和广播预算测试。
- 创建：`scripts/bench_workspace.ps1`，本地可重复性能基准入口。
- 修改：`android/app/src/main/java/com/phonebridge/MainActivity.kt`，后台解析和轻量 UI state 应用。
- 修改：`android/app/src/main/java/com/phonebridge/WorkspaceProtocol.kt`，版本化 envelope 与轻量投影解析。
- 修改：`android/app/src/main/java/com/phonebridge/BridgeLink.kt`、`OutboxSyncWorker.kt`，ACK、去重和合并同步。
- 修改：`android/app/src/main/java/com/phonebridge/MoteProfile.kt`、`MoteBehaviorEngine.kt`、`CompanionView.kt`、`RealityLensView.kt`，统一行为提示和关系驱动表现。
- 修改：`.github/workflows/ci.yml`、`README.md`、`HANDOFF.md`，性能门禁、运行说明和验收边界。
- 测试：对应 Node 测试、Android JVM 测试和协议兼容测试，不运行 ADB。

## 任务 1：建立红灯性能基线与协议契约

**文件：**
- 创建：`server/performance.test.js`
- 创建：`scripts/bench_workspace.ps1`
- 修改：`server/workspace-api.test.js`、`server/workspace-core.test.js`

- [ ] **步骤 1：先写失败测试**

新增断言：相同 revision 的 snapshot 序列化调用只能发生一次；`/api/state?view=summary` 返回 `view=summary`、`revision` 和不超过 25 KB 的 JSON；事件 burst 合并后只广播最后一个 revision；历史客户端继续获得完整 `snapshot`。

```js
test('caches one serialized snapshot per revision', async () => {
  const first = snapshotForRevision(12);
  const second = snapshotForRevision(12);
  assert.strictEqual(first, second);
});
```

- [ ] **步骤 2：运行红灯测试**

运行：`node --test server/performance.test.js`

预期：因 `snapshotForRevision`、summary view 或广播合并尚未存在而失败；不能把语法错误当作红灯依据。

- [ ] **步骤 3：定义最小测试入口**

在测试文件中只暴露测试所需的注入 seam，不改变生产 API；基准脚本调用 `node --test server/*.test.js` 并输出 JSON 指标：`summaryBytes`、`fullBytes`、`snapshotCacheHits`、`broadcastsPerSecond`。

- [ ] **步骤 4：运行基线并保存数值**

运行：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench_workspace.ps1`

预期：命令输出可解析的单行 JSON；基线值写入测试输出，不写入 token、运行时状态或设备信息。

- [ ] **步骤 5：Commit**

```powershell
git add server/performance.test.js server/workspace-api.test.js server/workspace-core.test.js scripts/bench_workspace.ps1
git commit -m "test: add workspace performance contracts"
```

## 任务 2：服务端 revision 快照、广播和持久化优化

**文件：**
- 修改：`server/index.js`
- 修改：`server/workspace-core.js`
- 修改：`server/task-runner.js`
- 测试：`server/performance.test.js`、`server/workspace-core.test.js`、`server/workspace-api.test.js`

- [ ] **步骤 1：实现 revision 缓存和 summary 投影**

增加 `snapshotCache = { revision, full, summary }`，仅在 workspace revision、任务、设备摘要、Mote 或自治摘要变化时失效；保留原 `/api/state` 字段，新增 `view=summary` 只返回首屏需要的统计、任务计数、活动 Mote 和连接状态。

```js
function cachedSnapshot(view = 'full') {
  if (snapshotCache.revision !== workspaceStore.eventRevision) rebuildSnapshotCache();
  return view === 'summary' ? snapshotCache.summary : snapshotCache.full;
}
```

- [ ] **步骤 2：实现广播合并**

将同步 burst 通过单个 `queueBroadcast(revision, payload)` 合并，在同一事件循环/短窗口内只发送最高 revision；保留 ACK、delta、`resetRequired` 和首次连接全量 snapshot。

- [ ] **步骤 3：实现合并持久化**

在 `WorkspaceStore` 内增加有界 debounce：普通状态变化 250 ms 内合并一次 snapshot 写入，关键审批、急停、奖励和任务终态立即 flush；journal 事件仍逐条追加，恢复顺序不变。

- [ ] **步骤 4：运行绿灯测试**

运行：`node --test server/performance.test.js server/workspace-core.test.js server/workspace-api.test.js`

预期：新增测试通过，旧 API 和旧测试全部通过，summary/full revision 连续且没有重复广播。

- [ ] **步骤 5：Commit**

```powershell
git add server/index.js server/workspace-core.js server/task-runner.js server/performance.test.js server/workspace-core.test.js server/workspace-api.test.js
git commit -m "perf: cache workspace snapshots and coalesce writes"
```

## 任务 3：Web 首屏和增量渲染

**文件：**
- 修改：`server/index.js` 内嵌 Web HTML/JS
- 修改：`server/performance.test.js`

- [ ] **步骤 1：先写失败的 HTML 行为测试**

断言 HTML 包含 summary 首屏入口、revision 事件处理器、`requestAnimationFrame` 合并器和低频 fallback；断言状态事件不再直接触发整块 workspace `innerHTML` 重建。

```js
assert.match(html, /requestAnimationFrame/);
assert.match(html, /workspace\.events|workspace\.summary/);
assert.doesNotMatch(html, /setInterval\(refreshWorkspace,1000\)/);
```

- [ ] **步骤 2：运行红灯测试**

运行：`node --test server/performance.test.js`

预期：旧 Web 代码仍命中重复刷新断言，测试失败。

- [ ] **步骤 3：实现 Web 分区更新**

保留现有 HTML 转义函数和 API 令牌流程；将任务、审批、Mote、日志和传感器区域拆为独立更新函数，以 `data-id` keyed 节点更新。WebSocket 事件先进入队列，单帧只应用一次；断线时以不高于每 5 秒的 fallback 请求恢复。

- [ ] **步骤 4：运行绿灯测试**

运行：`node --test server/performance.test.js server/*.test.js`

预期：HTML 契约、XSS 转义、鉴权、WebSocket 和全部服务端测试通过。

- [ ] **步骤 5：Commit**

```powershell
git add server/index.js server/performance.test.js
git commit -m "perf: render workspace updates incrementally"
```

## 任务 4：Android 后台解析、ACK 和同步去重

**文件：**
- 修改：`android/app/src/main/java/com/phonebridge/MainActivity.kt`
- 修改：`android/app/src/main/java/com/phonebridge/WorkspaceProtocol.kt`
- 修改：`android/app/src/main/java/com/phonebridge/BridgeLink.kt`
- 修改：`android/app/src/main/java/com/phonebridge/OutboxSyncWorker.kt`
- 测试：Android JVM protocol、reducer、outbox 和 behavior tests

- [ ] **步骤 1：先写失败 JVM 测试**

增加协议 envelope 版本解析、旧字段回退、重复 revision/eventId 去重和 ACK 超时测试。

```kotlin
@Test fun duplicateRevisionDoesNotProduceSecondUiState() {
    val reducer = WorkspaceReducer()
    assertTrue(reducer.apply(eventAt(8)))
    assertFalse(reducer.apply(eventAt(8)))
}
```

- [ ] **步骤 2：运行红灯测试**

运行：`.\gradlew.bat :app:testDebugUnitTest --tests '*Workspace*' --no-daemon --console=plain`

预期：新 reducer/ACK seam 尚不存在而失败。

- [ ] **步骤 3：实现最小后台处理链**

使用 `Dispatchers.Default` 完成 JSON parse 和 revision reducer，主线程只应用不可变摘要；`BridgeLink` 暴露明确的 ACK pending/timeout 结果；`OutboxSyncWorker` 以 `eventId` 去重、限制单次批量、遇到 revision gap 只触发一次恢复请求。

- [ ] **步骤 4：运行绿灯测试和构建**

运行：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`

预期：测试和 debug 构建成功；不运行 ADB、不安装 APK。

- [ ] **步骤 5：Commit**

```powershell
git add android/app/src/main/java/com/phonebridge/MainActivity.kt android/app/src/main/java/com/phonebridge/WorkspaceProtocol.kt android/app/src/main/java/com/phonebridge/BridgeLink.kt android/app/src/main/java/com/phonebridge/OutboxSyncWorker.kt android/app/src/test
git commit -m "perf: move workspace sync off the Android main thread"
```

## 任务 5：任务中枢和自治可观测性扩充

**文件：**
- 修改：`server/task-runner.js`
- 修改：`server/workspace-core.js`
- 修改：`server/index.js`
- 修改：Android workspace models/UI
- 测试：任务状态、审计、审批过期、急停和 API 测试

- [ ] **步骤 1：先写失败测试**

覆盖任务过滤、progress 事件、重试计数、审批剩余有效期、一次性 approval 消费和急停阻断新动作；硬禁止工具仍必须在审批之前拒绝。

```js
assert.equal(publicTask(task).retryCount, 1);
assert.equal(invokeApprovedTool(approval.id).accepted, true);
assert.throws(() => invokeApprovedTool(approval.id), /already consumed/);
```

- [ ] **步骤 2：运行红灯测试**

运行：`node --test server/task-runner.test.js server/workspace-api.test.js`

预期：新增 metrics/filter/approval 展示字段缺失而失败。

- [ ] **步骤 3：实现任务和自治摘要**

为公开任务增加 `queuePosition`、`retryCount`、`lastTransitionAt`、`lastError`；新增按状态筛选的只读 projection；审批返回脱敏参数摘要、`expiresAt`、消费状态和审计记录。急停状态继续作为全局硬门，不允许通过 UI 或自动化绕过。

- [ ] **步骤 4：实现 Web/Android 同源展示**

Web 和 Android 只消费服务端 projection；事件只传递变化的 task/approval item，状态缺口回退到 snapshot，不复制独立业务规则。

- [ ] **步骤 5：运行绿灯测试并 Commit**

运行：`node --test server/*.test.js`

```powershell
git add server/task-runner.js server/workspace-core.js server/index.js android/app/src/main/java/com/phonebridge
git commit -m "feat: expand task and autonomy observability"
```

## 任务 6：Mote 关系行为、性能门禁和文档收口

**文件：**
- 修改：`server/mote-expansion.js`、`server/mote-behavior.js`
- 修改：`android/app/src/main/java/com/phonebridge/MoteProfile.kt`、`MoteBehaviorEngine.kt`、`CompanionView.kt`、`RealityLensView.kt`
- 修改：`.github/workflows/ci.yml`、`README.md`、`HANDOFF.md`
- 测试：Mote relationship/quest/behavior、性能阈值、协议迁移测试

- [ ] **步骤 1：先写失败测试**

覆盖关系等级对行为提示的确定性影响、陪伴任务幂等奖励、未知形态回退和旧存档迁移；同一 interaction/eventId 不重复增加经验或刷新动画。

```kotlin
@Test fun relationshipLevelChangesReminderIntensityDeterministically() {
    assertNotEquals(behavior(level = 1).reminder, behavior(level = 5).reminder)
}
```

- [ ] **步骤 2：运行红灯测试**

运行：`node --test server/mote-expansion.test.js server/performance.test.js` 和 Android Mote JVM tests。

预期：新增关系等级映射或性能门禁缺失而失败。

- [ ] **步骤 3：实现统一行为提示**

关系等级只影响现有 `MoteBehaviorOutput` 的强度、提醒倾向、语气和粒子参数；Canvas 继续消费统一 hint，不添加大型资源，不为每个形态复制状态机。每日/阶段性陪伴任务使用服务端定义和 eventId 幂等入口。

- [ ] **步骤 4：接入 CI 和文档**

CI 执行 Node 全量测试、Android unit test、assembleDebug、性能基准、敏感扫描和 `git diff --check`；README/HANDOFF 明确性能预算、兼容策略、当前分支和实体机 QA 延后状态。

- [ ] **步骤 5：运行最终验证**

运行：

```powershell
node --check server/index.js
node --test server/*.test.js
.\gradlew.bat :app\testDebugUnitTest :app\assembleDebug --no-daemon --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench_workspace.ps1
git diff --check
```

预期：所有命令退出码为 0；敏感内容扫描无 credential-like 命中；不运行手机命令。

- [ ] **步骤 6：Commit**

```powershell
git add server android .github/workflows/ci.yml README.md HANDOFF.md
git commit -m "feat: finish PhoneBridge performance and companion expansion"
```

## 最终交付门禁

- [ ] 所有真实代码变更均有先红后绿的测试证据。
- [ ] `git status --short` 只包含已提交后的干净状态。
- [ ] 本地分支与 `origin/feature/integrated-enhancement` 的目标提交一致后才允许推送。
- [ ] 推送前再次执行 Node、Android、性能、敏感扫描和 diff 检查。
- [ ] 不声称完成实体机 QA；现实线索、文本聊天、PTT、断线恢复和崩溃检查继续列为后续手机验收。
