# PhoneBridge 全面深化：批次 1 实施记录

基线：`1a4acee`。分支：`feature/integrated-enhancement`。

## 已实现

- `MoteGrowthStore` 升级到可迁移的 v2 状态：保留旧 `daily`、`seenEventIds`，增加按日期保存的线索桶、业务收据和单调 revision。
- 线索事件 ID 支持 `reality[-lens]:v2:<Asia/Shanghai 日期>:<粗区域>:<类型>[:nonce]`；旧 `reality:` 与 `reality-lens:` ID 仍可读。
- 每个活动日期每种线索只结算一次；离线事件最多补交七天；过期事件生成拒绝收据且不发 XP。
- 奖励处理在持久化失败时回滚内存状态；重复 eventId 返回业务重复状态，不重复奖励。
- RealityEngine 事件 ID 日期化；`field-focus` 增益实际影响现实 XP；装备必须已拥有；任务领取检查完成条件。
- 增加 `GET /api/reality/progress` 和 `GET /api/reality/receipts/:eventId`。
- WebSocket ACK 增加 `businessStatus`、`businessAccepted`、`reason`、`resultRevision`，旧 `accepted/status/revision` 字段保留。
- Android outbox 增加 Room v4 租约、发送时间、业务状态、拒绝原因和结果 revision；纯逻辑队列支持 claim、ACK 超时、指数退避和终态收据。
- Activity 不再直接发送 outbox，统一交给唯一 WorkManager worker；线索奖励改为服务端确认后落本地成长经验。
- Android 生成带日期的 v2 线索 ID，并兼容 Boolean、数字 `0/1` 和字符串线索字段。

## 验收

- Node：`node --test server/*.test.js`，112/112 通过。
- Android：`./gradlew :app:testDebugUnitTest --tests com.phonebridge.WorkspaceProtocolTest` 通过；Reality 模型/探索定向测试通过。
- 实体机、正式 keystore、真实 ARCore 和长时间运行不在本批代码验收内。

## 恢复与回滚

旧 Mote 状态由 `MoteGrowthStore` 启动时迁移；历史 `seenEventIds` 会被登记为已处理，避免重复奖励。若需要回滚代码，保留运行时状态并回退本批提交，不删除用户目录。
