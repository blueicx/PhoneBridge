# PhoneBridge 全面深化批次 3：伙伴与任务空间

## 实施内容

- 新增 `server/proactive-policy.js`：主动提醒默认每小时最多 1 次、每天最多 6 次；固定 23:00–07:00 静默，支持专注态、即时静音、重复键去重、持久化和可解释抑制原因。
- 保持 `/api/proactive` 旧顶层字段兼容，并新增 `/api/proactive/explain`、`/api/proactive/mute`；被抑制的提醒保留在收件箱，但不向手机实时广播或朗读。
- `MemoryStore` 增加 `candidate/confirmed` 状态、候选确认、状态过滤和 `selectForConversation`；自动候选不会自动进入对话上下文，支持 `remember=false` 的本轮不记忆请求。
- 工作区聊天任务记录 `remember` 选择；Android AI 空间增加“记住”开关和任务开始/暂停/继续/重试/取消/归档按钮，所有动作带幂等键并复用服务端任务取消/重试链路。
- Android 普通聊天增加本轮记忆开关；语音前台状态把准备、监听、处理中和播报状态显示到音频指标。

## 验收

- Node 全量：`node --test server/*.test.js`，120/120 通过。
- Android：`:app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`，`BUILD SUCCESSFUL`。
- 本批不宣称实体机重新验收、正式签名、ARCore/GPS 或两小时运行完成。

