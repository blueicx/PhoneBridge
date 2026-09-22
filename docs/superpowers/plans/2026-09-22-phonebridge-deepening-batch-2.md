# PhoneBridge 全面深化批次 2：模块拆分与加载性能

## 目标

在不改变既有接口和单用户边界的前提下，把现实奖励协调从服务端入口下沉为可测试模块，并让 Android 和 Web 的增量投影在突发事件下减少重复重建。首屏摘要优先，完整快照按需生成。

## 实施内容

- 新增 `server/reality-coordinator.js`，统一 Mote 线索、成长收据、现实增益同步和进度投影；入口只保留路由和广播适配。
- 新增协调器单测，覆盖奖励状态、进度投影和精确区域拒绝，避免奖励流程在路由中重复实现。
- 扩展 `RevisionSnapshotCache`，支持独立 `buildSummary`；`GET /api/state?view=summary` 不再为了生成轻量摘要物化完整快照，仍保持旧 `build` 调用方式兼容。
- 将快照缓存的视图构建次数加入 `/api/diagnostics` 和诊断导出，便于观测摘要/完整视图是否发生不必要构建。
- Android `CompanionSessionRepository` 增加突发时间线批量合并；`MainActivity` 收集同一 WebSocket 批次的时间线事件后一次投影、一次 UI 发布，旧事件协议仍逐条兼容。

## 验收

- Node 全量：`node --test server/*.test.js`，115/115 通过。
- Android：`android\\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`，`BUILD SUCCESSFUL`。
- `git diff --check` 通过。
- 本批不宣称 ARCore 真平面、GPS fix、正式签名 APK 或实体机长时间运行完成；这些继续保留为后续独立验收项。

