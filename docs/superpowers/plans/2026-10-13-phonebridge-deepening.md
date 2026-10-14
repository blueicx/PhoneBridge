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
- 批次 4：实现提交 `00d6176e9ee6846b10bf5335dac7b36fbf2c57b9` 已推送；GitHub Actions [run 38543293259](https://github.com/blueicx/PhoneBridge/actions/runs/38543293259) 的 Node、Android、API 34 emulator 与 APK verify 四个 job 全成功。本地 Node **297/297**、Android JVM **196/196**、Lint/Debug 构建、Chromium、内部 APK 门禁、秘密扫描、脚本语法及 diff 检查均通过。文档收尾提交 `2cf910786076b0174b11f1ecc862bbe4d89c617b` 的 Actions 状态需在继续推送前核实。
- 批次 5：本机实现与自动验收通过，实现提交 `d926be1` 已本地创建、尚未推送。20 个 Mote 的三章双结局剧情、12 种四动作遭遇、双端结果记录已落地；Node **312/312**、Android JVM **199/199**、Lint/Debug 构建、隔离 Chromium、APK 门禁、秘密扫描与性能预算通过。推送后的最新 SHA Actions 和 Xperia 实机验收待完成。
- 批次 6：本地恢复预检与 CI 接线已通过，尚未最终完成。`scripts/test_runtime_backup.ps1` 覆盖 v3 清单/哈希、VerifyOnly、旧 v2 迁移、故事/成长/Reality 状态备份、恢复后真实持久化重开和重复事件去重、隐私字段排除、损坏/缺失/路径穿越/空备份拒绝及恢复回滚；GitHub Actions 已加入恢复演练，workflow 契约测试通过。本轮最终本机回归 Node **312/312**，Android JVM **199/199（51 suites）**、Lint、Debug 构建成功；秘密扫描和差异检查通过，性能 `elapsedMs=0.797`、快照 1694/48 bytes、10000 次缓存命中、突发广播 1 次。Debug APK 内部门禁在干净树通过，`2.2.0`/code `4`、94,776,366 bytes，SHA-256 `60c654437cce4c4118478b7aae019714dd02bb0ebb55681850738203621090c5`，附本地校验 manifest（记录源提交 `ec0eeb1`）。实现/测试提交 `e046832` 已在本地创建、尚未推送。无线 ADB `192.168.101.68:39663` 不可达，隔离 ADB 5038 无设备；远端最新 SHA Actions、GitHub artifact 与实机验收待完成。真实 AR/长时稳定性及真实在线 provider 验收保持单独待办。推送顺序受前置 Actions 核验约束。

## 验收

每批运行相关自动测试，更新 README/HANDOFF，独立提交推送。最终运行 Node 全量、Android 单测/Lint/Debug、Room 迁移、协议/敏感扫描、浏览器交互和最新提交 CI。设备可用后记录冷启动、帧耗时和两小时资源数据；缺少证据的实机项目保留待验收。正式签名受独立加密恢复副本门禁约束。
