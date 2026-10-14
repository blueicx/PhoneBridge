# PhoneBridge 大版本深化实施账本

计划基线：11b5510；当前分支：feature/integrated-enhancement。Codex 独立执行，不使用子 agent。

## 已批准的批次

1. 加载、后台与 CI 提速：请求去重/取消、可见性调度、WS 更新、资源懒加载、CI 并行。
2. 舞台操作：直接聊天、流式气泡、停止重试、最多两张任务/提醒/活动卡片、交互反馈。
3. 连续对话：真实 provider 流式和上游取消、会话摘要、记忆冲突合并、统一语音状态。
4. 家园成长：12 件布局、明确视觉类型、12 件道具用途、配方材料预览、活动和成长记录。
5. 角色探索：20 个角色四种反馈、20 段三步双结局剧情、12 种遭遇、结果日志和镜头提示。
6. 恢复交付：新状态备份迁移、恢复演练、诊断、性能与实机、最新 SHA CI 和构建产物。

## 进度

- 批次 1：提交 `cfe0f97` 已推送；Node **250/250**、无头 Chromium、Android 单测/Lint/Debug 构建通过；GitHub Actions run **38350063660** 全部成功。
- 批次 2：提交 `71f55e517b179e2ae878facbbafb02546a0694c7` 已推送；Node、Android、API 34 emulator 与 artifact verify 在 GitHub Actions run **38531370595** 全部成功。Web/Android 舞台聊天、任务/审批/活动卡和失败恢复已接通。
- 批次 3：提交 `6377e763c0c277bfb792841276a2f6268d97f660` 已推送；GitHub Actions run **38536709334** 全部成功。Node **276/276**；Android JVM **189/189（49 suites）**、Lint、Debug 构建通过；真实本地服务 + Chromium、模拟 SSE、性能预算和敏感扫描通过。覆盖 provider 真流式/取消、会话恢复与隐私隔离、记忆来源/冲突/批量确认与合并、Android 离线记忆同步状态。
- 批次 4：家园布局 v2、双端布置/交互、12 件道具实际效果、配方材料预览、活动成长记录及不确定回执原 ID 重试已实现。本地 Node **297/297**、Android JVM **196/196**、Lint/Debug 构建、Chromium、内部 APK 门禁、秘密扫描、脚本语法及 diff 检查均通过；独立提交推送后核对最新 SHA 的 Actions。
- 批次 5–6：待实施。实机和真实在线 provider 验收保持单独待办。

## 验收

每批运行相关自动测试，更新 README/HANDOFF，独立提交推送。最终运行 Node 全量、Android 单测/Lint/Debug、Room 迁移、协议/敏感扫描、浏览器交互和最新提交 CI。设备可用后记录冷启动、帧耗时和两小时资源数据；缺少证据的实机项目保留待验收。正式签名受独立加密恢复副本门禁约束。
