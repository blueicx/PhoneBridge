# PhoneBridge Round 63 设计规格：CI 可靠性、工作台体验与增量同步

**状态：** 已获用户确认，2026-09-10

## 目标

在 Round 62 的快照缓存、摘要接口和事件去重基础上，完成一轮可验证的可靠性与体验增强：让 GitHub Actions 能在干净 runner 上真实运行，降低 Web 工作台重复传输和重绘，补齐任务工作台的可操作信息，并让 Android 重启后继续使用正确的同步游标和 Mote 行为提示。

## 范围与约束

- 继续使用 `F:\\CodexApps\\PhoneBridge-worktrees\\integrated-enhancement` 和现有 `feature/integrated-enhancement` 分支。
- 只面向单用户、单 PhoneBridge 节点；不增加账号、多设备或外部 AI 服务依赖。
- 保留现有事件 envelope、`eventId`/revision 幂等、自治白名单和硬禁止规则。
- 不执行 ADB、APK 安装、现实线索点击、文本聊天或 PTT 实机回归。
- 不把 `server/access.token`、运行时 state、日志、截图、APK 或依赖缓存提交到公开仓库。

## 方案

### CI 可靠性

GitHub runner 在 Node 测试前执行 `npm ci --prefix server`，并以 `server/package-lock.json` 作为缓存依赖键。Node 测试仍从仓库根目录枚举 `server/**/*.test.js`，保证本地和 CI 使用同一入口。Android 和性能预算步骤保留原有顺序。

### Web 工作台

服务端继续以 workspace revision 生成 full/summary 快照和 ETag。内联 Web 客户端为 GET 请求按 URL 保存 ETag，收到 304 时跳过 JSON 解析和 DOM 更新；完整状态与工作台摘要分开轮询，工作台只在 revision 变化时重建任务、审批和 Mote 区域。任务详情继续从内存索引更新，避免重复请求完整快照。

### 任务中心

复用现有任务 API 和审计 API，在 Web 工作台增加状态统计、状态筛选、选中任务的结果/错误/运行指标和审计摘要。所有动作仍通过现有幂等键和状态转换校验，失败响应必须显示在工作台而不是静默吞掉。

### Android 同步与 Mote

`WorkspaceEventGate` 在进程启动时从已持久化游标初始化，并在 snapshot 后清除 gap 状态；事件仍按 `eventId` 和严格递增 revision 去重，发现 gap 时请求 snapshot。Android 解析服务端的 `reminderStrength`，交给现有 Companion/RealityLens 行为提示，不改变旧 wire 字段的默认行为。

## 数据流

```text
WorkspaceStore revision
        ├─ full snapshot + ETag ── Web 状态区
        ├─ summary snapshot + ETag ─ Web 工作台
        └─ workspace.events ─────── Android EventGate ─ Room/界面

任务动作 ─ API 幂等校验 ─ WorkspaceStore ─ workspace.task/audit ─ Web/Android
```

## 验收标准

1. GitHub Actions 在干净 runner 上完成依赖安装、Node 测试、性能预算、Android 单元测试、Debug 构建和空白检查。
2. Node 全量测试通过；新增断言覆盖 ETag 304、任务工作台筛选/审计和依赖安装入口。
3. Web 的 304 响应不触发状态 JSON 解析或工作台 DOM 重绘；任务按钮仍可执行并显示错误。
4. Android 单元测试覆盖游标恢复、gap snapshot 恢复、重复事件去重和 `reminderStrength` 解析；`assembleDebug` 通过。
5. 敏感扫描、`git diff --check`、工作树检查通过；最终只上传功能分支。
6. 实机验证明确记录为未执行。

