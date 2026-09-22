# PhoneBridge 交接文档（Round 57 综合基线 / Round 63 收口）

更新时间：2026-09-08（以证据文件与测试验证时间为准）

## 0. Round 58 当前状态覆盖（2026-09-08）

- GitHub 公有仓库：<https://github.com/blueicx/PhoneBridge>，默认分支为 `main`。
- 历史硬件基线提交：`6e510cd`；当前开发分支最新提交以 `feature/integrated-enhancement` 为准。已清除脚本中的硬编码设备 PIN；设备 PIN 仍需在手机端自行更换，严禁重新写入代码或文档。
- 本地验证：`node --check server/index.js`、`node --test server/*.test.js` 通过；Android `:app:testDebugUnitTest :app:assembleDebug` 返回 `BUILD SUCCESSFUL`。
- 当前实机状态：Windows 能识别 Xperia XZ2，但 ADB 5038、5039、5037 均未列出已授权设备。因此现实线索点击、文本聊天和 PTT 回归仍未完成，不能用历史截图代替本轮验证。
- 恢复条件：解锁手机、确认 USB 调试授权后，再运行 `scripts/adb_recovery.ps1 -ResetServer`，随后按第 5 节顺序继续。

## 1. 项目位置与结构

- 工程根目录：`F:\CodexApps\PhoneBridge`
- Android 工程：`F:\CodexApps\PhoneBridge\android`
- 本地服务节点：`F:\CodexApps\PhoneBridge\server`
- 自动化与运维脚本：`F:\CodexApps\PhoneBridge\scripts`
- 注：本项目当前由 Git 管理并同步到 GitHub；版本历史与演进基线以 Git、本文档、`.dispatch-progress.md` 及 `.superpowers/sdd/progress.md` 为准；重要节点备份存储在 `.superpowers/dispatch-backup/`。

---

## 2. 双轨系统基线 (Dual-Track Baseline)

为解决历史交接中文档与代码演进脱节的问题，本项目明确建立**双轨基线机制**：

### 轨道 A：实体机已验证硬件基线 (Physical Hardware Baseline - Round 57)
- **目标设备**：Sony Xperia XZ2 (产品代号: H8296，设备序列号: `QV7017NH1F`)
- **已刷入并验证的 APK**：
  - 文件路径：`F:\CodexApps\PhoneBridge\android\app\build\outputs\apk\debug\app-debug.apk`
  - 文件大小：`94,859,501` bytes
  - SHA-256：`F10E561EA7CD373E06E26D3BEE875C973951EF922BF03DA15F886AD2F52FD7F5`
  - 构建状态：`:app:assembleDebug` 与 `:app:testDebugUnitTest` 全部 SUCCESSFUL
- **实机已验证能力**：
  - **类 Pokemon GO 现实空间 3D 生物物理锚定与实机渲染**：在真实 Xperia XZ2 相机画面中，Mote（利姆鲁与云鲸形态）稳稳站在现实世界的木质桌面上，拥有透视椭圆柔和地面阴影、立体高光、萌态腮红与实时注视镜头的目光追踪 (`w4_ar_companion_reality_lens.png`)。
  - **Pokemon GO 式边缘雷达指针指示**：当线索在视野外时，屏幕边缘实时呈现指向性雷达脉冲箭头与角度距离标签（如 `[光线样本 63°]`），引导玩家转动身体追寻。
  - **实机 AR 交互闭环与升级验证**：实机点击现实空间中的 Mote 与线索，成功触发跳跃、对话与触觉反馈，伴侣成功从 Lv.2 升至 **Lv.3**，解锁新技能「任务直觉」，好感度提升至 85，经验提升至 12/135 (`w4_ar_companion_clue_collected.png`)。
  - **权限加固与系统稳定性**：补充 `android.permission.VIBRATE` 并增加 `runCatching` 防御，实机 logcat 无 `FATAL EXCEPTION`、无崩溃、无 ANR。

### 轨道 B：架构与协议演进基线 (Architecture Baseline - Round 55 ~ Round 57)
- **Round 55 (AI Space & 伴侣驾驶舱)**：
  - 引入 `AttentionItem`、`AutonomyPolicy`、`ActionRun` 核心领域模型。
  - 建立持续听音前台服务生命周期与 Room 2.6.1 数据库持久化。
- **Round 56 (独立设备健康与连接退避)**：
  - 引入 `DeviceHealthState.kt` 与 `server/device-health.js`，将网络连接、传感器、模型、鉴权与任务状态独立管理。
  - 引入 1s ~ 60s 有界指数重连退避策略，硬鉴权失败立即停止无效重试。
- **Round 57 (类 Pokemon GO 现实空间生物模型与空间探索实装)**：
  - **现实空间 3D 伴侣模型物理锚定**：重构 [`RealityLensView.kt`](file:///f:/CodexApps/PhoneBridge/android/app/src/main/java/com/phonebridge/RealityLensView.kt)，将 Mote 从过去的死板角落贴纸彻底升级为**真实世界中物理锚定的 3D 立体生命体**（锚定在玩家正前方地面/桌面，含真实地面投影、立体球形光影质感、自然呼吸浮动与注视镜头目光追踪）。
  - **支持全部 6 大外形体态的 3D AR 投射**：利姆鲁（透光果冻史莱姆+腮红）、叶狐（萌狐双耳与灵动身姿）、雾猫（浮空猫尾与灵气胡须）、云鲸（破浪侧鳍与气孔光晕）、机甲兽（光环环绕与状态灯）及原版 Mote（星核光晕与公转星尘）。
  - **AR 触摸/跳跃/抚摸互动**：玩家在相机画面中直接点击真实空间中的 Mote，触发向上轻巧跳跃、开心眨眼笑颜、爱心与闪光粒子漫天飞舞、头顶现实气泡对话，并激发 Xperia XZ2 索尼线性马达真实触感震动（好感+2，经验+1）。
  - **全景 360° 空间线索与 Pokemon GO 边缘雷达指针**：3DoF 空间陀螺仪传感器融合 (`Sensor.TYPE_ROTATION_VECTOR`)，当线索或 Mote 离开镜头视野时，屏幕边缘以脉冲动态箭头与角度距离引导玩家转动身体追寻。
  - **最新实装构建 APK**：
    - 产物路径：`F:\CodexApps\PhoneBridge\android\app\build\outputs\apk\debug\app-debug.apk`
    - 文件大小：`94,859,501` bytes
    - SHA-256：`F10E561EA7CD373E06E26D3BEE875C973951EF922BF03DA15F886AD2F52FD7F5`
    - 验证：`:app:assembleDebug` 与 `:app:testDebugUnitTest` 均 SUCCESSFUL。
  - **服务端登录页运行时收尾**：动态呈现非敏感节点端口、运行时长、手机连接徽标与服务状态，单测套件持续保持 **24/24 全绿**。
  - 新增专用运维与 QA 工具链（详见第 3 节）。

---

## 3. 运维与验证工具链 (Tooling Runbook)

所有辅助脚本均存放在 `F:\CodexApps\PhoneBridge\scripts` 目录下：

### 3.1 ADB 掉线自愈与无线调试助手 (`scripts/adb_recovery.ps1`)
专门解决老旧 Xperia XZ2 物理 USB-C 接口松动导致 ADB 5038/5039 掉线的问题。
```powershell
# 运行诊断并尝试恢复连接、唤醒、解锁并建立 9503 反向代理
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/adb_recovery.ps1

# 重置 ADB 守护进程并探测
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/adb_recovery.ps1 -ResetServer

# 自动探测手机 WLAN IP 并切换到无线调试 (免插拔)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/adb_recovery.ps1 -EnableWifiAdb
```

### 3.2 静态交接与动态 Mote 记忆同步 (`scripts/sync_handoff.py`)
将本交接文档的 `当前任务` / `下一步` / `约束` 自动提取并注入 `server/handoff.json` 及 `/api/handoff`，让手机端 Mote AI 对话实时感知工程交接上下文。
```powershell
# 提取并在终端预览
python scripts/sync_handoff.py --dry-run

# 执行同步并热推到正在运行的 Mote 节点
python scripts/sync_handoff.py
```

### 3.3 现实镜头 3 线索自动化点击与奖励验证 (`scripts/test_reality_clues.ps1`)
按设备分辨率自动计算 `地点线索` (26%/30%)、`物体轮廓` (66%/44%)、`光线样本` (38%/64%) 坐标并模拟点击，校验 +4 XP 奖励与日志。
```powershell
# 手机连接并打开 Mote 后执行
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test_reality_clues.ps1
```

---

## 4. 节点启动与网络接入

### 4.1 本地节点启动 (PowerShell)
```powershell
& 'C:\Users\blueice\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' 'F:\CodexApps\PhoneBridge\server\start_detached.py'
```

### 4.2 USB 反向代理映射 (端口 9503)
```powershell
& 'C:\Users\blueice\AppData\Local\Android\Sdk\platform-tools\adb.exe' -P 5038 reverse tcp:9503 tcp:9503
```
- App 默认节点地址：`ws://127.0.0.1:9503`
- Web 控制台地址：`http://127.0.0.1:9503`
- 鉴权令牌保存在 `server/access.token`。**严禁将真实 Token 写入文档、提交或对外日志中**。

---

## 5. Round 60：完整增强实现状态

- 已实现个人任务中枢增强：任务状态新增 `archived`，工作台支持暂停、继续、重试、取消、归档；任务完成/失败/取消继续生成 Attention，ActionRun 保留过期、急停和脱敏审计。
- 已实现全局自治白名单：`GET/PATCH /api/autonomy`，默认只读工具；未注册、Shell、删除、凭据、发布及不可逆工具硬禁止，自动化动作额外检查全局白名单。
- 已实现 10 个 Mote 配置和持久化探索状态：6 个初始形态、4 个探索形态；三类碎片解锁、`eventId` 去重、旧状态迁移和 WS `mote.roster` / `mote.profile` / `mote.exploration`。
- 已实现 Android `MoteProfile`、`MoteBehaviorEngine`、新旧 `PetAppearance` 兼容、WS 协议常量、离线 JSON 镜像与 outbox 去重；新增 Mote 图鉴入口，Canvas 读取统一行为提示。
- 本轮新增严格任务状态转换、幂等任务动作、任务审计查询、事件 revision/增量同步、明确重复 ACK、自治策略 revision/usesRemaining/参数约束、Mote 行为 wire payload，以及 Android WorkManager outbox 恢复和 Room 策略迁移。
- 新增接口：`POST /api/tasks/:id/actions`、`GET /api/tasks/:id/audit`、`GET /api/workspace/events?since=N`、`GET /api/motes/behavior`。
- 服务端测试：35/35 通过。Android `:app:testDebugUnitTest` 与 `:app:assembleDebug` 已通过；本轮未连接实体机，未宣称手机验收。

## 6. Round 61：五批次扩充收口（2026-09-10）

- 设计规格与实现计划：`docs/superpowers/specs/2026-09-10-phonebridge-expansion-design.md`、`docs/superpowers/plans/2026-09-10-phonebridge-expansion.md`。
- 新增服务端 `TaskRunner`：单并发排队、暂停/继续、取消、瞬时失败重试、进度和运行状态；会话消息任务通过队列执行。
- 新增自治审批：受限工具生成一次性 approval，批准后消费一次；过期、重放、参数越权和硬禁止仍拒绝，急停会取消活动任务。
- 新增同步恢复窗口：事件保留上限、delta/snapshot `resetRequired` 响应和明确 revision 范围；Android 新增审批、关系、任务和同步事件协议常量。
- 新增 Mote 关系与陪伴任务：互动 eventId 幂等、等级经验持久化、quest claim 幂等；Web/Android 快照带关系与任务摘要。
- 新增 `.github/workflows/ci.yml`：Node 测试、Android `testDebugUnitTest`、`git diff --check`；CI 不连接实体机。
- 本轮本地验证：服务端测试 43/43；Android `:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过；敏感扫描通过，GitHub 推送待收尾验证。
- 重要边界：本轮未运行 ADB、现实线索点击、文本/语音实机回归；旧 Round 58 提交记录属于历史硬件基线，当前开发基线以功能分支最新提交为准。

## 7. Round 62：性能与功能扩充当前状态（2026-09-10）

- 服务端已增加按 workspace revision 的 full/summary 快照缓存、`/api/state?view=summary`、ETag 304、快照广播合并和 WorkspaceStore 250 ms 有界持久化调度。
- Web 首屏工作台轮询从 1 秒/220 ms 降为 3 秒，详情轮询改为本地内存更新，工作台请求使用 summary projection 并以 `requestAnimationFrame` 合并更新。
- TaskRunner 公开运行指标，Android 增加 WorkspaceEventGate，按 revision/eventId 去重并在 gap 时请求 snapshot；outbox 唯一任务使用 KEEP，避免重复排队。
- Mote 行为提示现在根据关系等级确定性增加 reminderStrength；CI 增加工作区性能预算和 Android debug assemble。
- 本轮验证：服务端 `node --check server/index.js` 与 `node --test server/*.test.js` 为 49/49；摘要 API 的 ETag/304 集成断言通过；性能预算输出 `summaryBytes=48`、`snapshotCacheHits=10000`、`broadcastsAfterBurst=1`。
- 本轮实体机仍未验证；没有运行 ADB、安装 APK、文本/语音实机回归或现实线索点击。
- 验证入口：`node --test server/*.test.js`、`scripts/bench_workspace.ps1`、`android\\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`。

## 8. Round 63：工作台与同步性能增强（2026-09-10）

- CI 修复：GitHub Actions 在 Node 测试前执行 `npm ci --prefix server`，并启用 `server/package-lock.json` 的 npm 缓存，避免干净 runner 缺少 `ws` 依赖导致工作流失败。
- Web 工作台：完整快照和摘要快照均使用条件请求；客户端保存 ETag，收到 `304` 时跳过 JSON 解析和 DOM 重绘。任务面板增加状态统计、状态筛选、任务详情、结果/错误、运行指标和最近审计记录；动作按钮支持禁用反馈与错误回显，并保留归档入口。
- Android 同步：启动时恢复持久化 workspace revision；`WorkspaceEventGate` 对 revision gap 提供一次性消费并触发 snapshot，避免断线恢复后的重复拉取。Mote 行为的 `reminderStrength` 按浮点值解析并归一化到 `0..1`，同时作用于伴侣与现实镜头的动画提示。
- 本轮独立验收：`node --check server/index.js` 通过；`node --test server/*.test.js` 为 **49/49**；性能基准输出 `elapsedMs=0.764`、`summaryBytes=48`、`snapshotCacheHits=10000`、`broadcastsAfterBurst=1`；Android `:app:testDebugUnitTest` 与 `:app:assembleDebug` 均 `BUILD SUCCESSFUL`；`git diff --check` 通过。
- 远端验收：提交 `bfa52fd` 触发的 GitHub Actions run `34493572583` 已通过，Node、性能、Android 单测、Debug 构建和差异检查全部成功；GitHub 仅提示 actions 使用 Node 20 的弃用警告，不影响本次结果。
- 验收边界：本轮没有运行 ADB、安装 APK、现实线索点击、文本聊天、PTT 或断线实机回归；手机实机验收仍留待后续。

## 9. Round 64：第一轮全面增强收口（2026-09-12）

- **统一 workspace timeline/projection**：建立 `server/workspace-timeline.js`，统一输出 task、chat、attention、mote、health、autonomy 事件；事件同时提供新协议 `entity/createdAt` 与旧兼容字段，按 `eventId` 幂等，支持 monotonic revision、实体版本、游标分页、delta/snapshot 恢复和删除操作，提供 `GET /api/workspace/timeline` 并支持 ETag 304 缓存。现有 workspace 状态会初始化到时间线快照。
- **可插拔 AI provider 与本地离线回退底座**：建立 `server/ai-provider.js`，支持 Codex、OpenAI-compatible、Gemini-compatible 以及本地离线规则引擎兜底；显式 provider 已接入实际聊天路径，失败只回退本地离线规则，备用联网 provider 不自动调用；配置与日志凭证严格脱敏；提供 `GET /api/ai/providers`、`GET/PATCH /api/ai/settings`、`POST /api/ai/providers/:id/probe`。
- **诊断与性能边界**：建立 `server/diagnostics.js` 与 `GET /api/diagnostics`，统一输出启动耗时、同步延迟、事件积压、设备遥测（电量/内存/温度/网络）、provider 延迟与降级计数；保留 30fps 前台目标与后台降频策略。
- **Web 控制台全视图与深链**：工作台增加收件箱/进行中/历史任务视图与状态筛选；实现任务与关联聊天会话上下文详情展示；Attention 项增加 `data-attention-id`、`data-task-id`、`data-deep-link` 属性并支持点击跳转对应任务；增量拉取时间线与诊断摘要，304 及无变动事件跳过 DOM 重绘。
- **Android 双端状态对齐**：新增 `WorkspaceTimeline.kt`（StateFlow 投影更新、任务卡片协议、深链协议、断线缓存间隙检测与快照恢复、GPS 占位输入底座）与 `AiProvider.kt`（脱敏配置、离线本地回退解析器、探针解析）；`MainActivity` 已消费 timeline 快照与 `workspace.timeline` 增量事件，不改动既有 Canvas 形态渲染分支。
- **共享协议测试与 Fixture**：新增 `protocol-fixtures/workspace-timeline.json`、Node 回归与 API 集成测试、Android 单元测试与 Debug APK 编译验证；本次独立回归目标为 Node **66/66**、Android `:app:testDebugUnitTest` 与 `:app:assembleDebug`、工作区性能基准、`git diff --check` 和敏感内容扫描。
- **红线与安全边界**：本轮未连接实体机，未执行 ADB、安装 APK、现实线索点击或 PTT 语音实机回归；未实现第二轮 AR/GPS 玩法；密钥不进入协议/日志/Git；完成独立审查后再推送 Git 远端。

## 10. 当前阻塞点与下一步行动 (Next Steps)

1. **当前阻塞点**：
   - 实体机在 Windows 设备管理器中显示为 `USB\VID_0FCE&PID_0DDE\QV7017NH1F`，但 ADB 端口（5038）列表暂时为空（设备因电量保护或 USB 调试鉴权掉线）。
2. **下一步执行动作（按优先级）**：
   - **Step 1（恢复连接）**：重新插拔 USB-C 数据线或在手机端解锁并确认“允许 USB 调试”，运行 `scripts/adb_recovery.ps1 -EnableWifiAdb` 固化无线连接。
   - **Step 2（现实镜头 QA）**：运行 `scripts/test_reality_clues.ps1`，验证 3 个线索的点击反馈、+4 经验值累加及技能进度。
   - **Step 3（语音与文本回归）**：在当前 APK 上执行一次文本聊天和 PTT 语音对讲回归，确认 Vosk 离线识别与云端 TTS 链路顺畅。
   - **Step 4（电源策略恢复）**：实测完成后，在实体机恢复正常熄屏休眠策略：
     ```powershell
     & 'C:\Users\blueice\AppData\Local\Android\Sdk\platform-tools\adb.exe' -P 5038 -s QV7017NH1F shell svc power stayon false
     ```

## 11. 批次 A：可靠性与发布闭环（2026-09-18）

- `server/runtime-persistence.js` 提供 schema v3、schema v2/旧裸 JSON 迁移、SHA-256 快照校验、原子写入、有限 `.bak.N` 备份、损坏快照隔离和最近备份恢复。
- `WorkspaceStore`、`WorkspaceTimeline`、Mote 主状态、关系/任务状态和 provider 非敏感设置接入同一持久化管理器；API key、token、Cookie、密码和 Authorization 不进入状态文件。
- 新增 `server/health.js` 和 `/health/live`、`/health/ready`（同时提供 `/api/health/liveness`、`/api/health/readiness`）；诊断输出包含 schema/recovery 统计。
- 新增 `server/structured-log.js`，运行日志以 JSON 记录并做敏感值脱敏；`/api/auth/rotate` 不再在 HTTP 响应中回传新令牌，令牌文件启动/轮换时强制尝试 `0600` 权限。
- 新增 `scripts/backup_runtime.ps1`、`scripts/restore_runtime.ps1`、`scripts/scan_secrets.ps1`；备份排除令牌、日志、画面、APK 和临时运行文件。
- CI 增加协议回归、性能预算、敏感扫描、Android Debug 构建和干净工作树检查，Node runner 更新为 22。
- 独立验证：Node `74/74` 通过；性能基准 `elapsedMs=1.173`、`fullBytes=1694`、`summaryBytes=48`、`snapshotCacheHits=10000`、`broadcastsAfterBurst=1`；备份/恢复脚本在临时目录验证通过；`git diff --check` 和敏感扫描通过。
- 本轮仍未连接实体机，未运行 ADB、安装 APK、AR、PTT、现实线索点击或长时间温度/电量回归。

- 收口提交：`36bed8b`（批次 A 实现）、`5fe5688`（Linux 子进程退出后的临时目录清理）、`0bab242`（CI 不再改变 `gradlew` 文件模式）。远端 `feature/integrated-enhancement` 已与 `0bab242235090eb23bd389bd1b8de477494b03b3` 一致。
- GitHub Actions run `35348080142` 已成功，Node、协议、性能、敏感扫描、Android 单测、Debug 构建、差异检查和干净工作树均通过；只有 GitHub 关于 actions 使用 Node 20 的弃用提示。
- 当前工作树干净，`main` 未修改；Antigravity 编排接口仍遗留 `executing/acceptance` 状态，但实际文件审查、修复、测试、提交和远端验收已由 Codex 完成。

### 批次 A 后续收尾

1. 手机恢复后，按计划补做三类现实线索、文本聊天、PTT、通知、小组件、断线恢复、长时间运行、温度和电量检查。
2. 批次 B 开始前重新展示计划并等待确认；不修改 `main`。

## 12. PhoneBridge 2.0 当前实施状态（2026-09-22）

- 已写入执行计划：`docs/superpowers/plans/2026-09-22-phonebridge-2.0.md`。
- 已加入确定性 `server/device-simulator.js` 与 Android `DeviceSimulation.kt`，覆盖粗区域、现实事件、网络、传感器、电量、温度和帧率模拟；生产默认关闭。
- AI provider 已增加能力描述、requestId、取消、流式分块、本地预算和脱敏长期记忆存储；新增 `/api/ai/capabilities`、`/api/ai/requests/:id/cancel`、`/api/memories`。
- 现实引擎已加入 `server/reality-engine.js`：40 个区域事件、20 种遭遇、40 件材料/道具、20 个配方、30 件装饰和 36 条任务；奖励、库存、家园与事件均按幂等状态处理。
- Mote 图鉴服务端和 Android 配置扩展到 20 个形态；新增形态仍消费统一行为提示，尚未逐一进行实体机视觉验收。
- 当前验证：Node 全量测试 **91/91**；性能预算输出 `elapsedMs=1.067`、`fullBytes=1694`、`summaryBytes=48`、`snapshotCacheHits=10000`、`broadcastsAfterBurst=1`；Android `:app:testDebugUnitTest :app:assembleDebug` 返回 `BUILD SUCCESSFUL`；敏感扫描、`node --check server/index.js` 和 `git diff --check` 均通过。
- 新增本地一次性配对模块 `server/pairing.js` 与 Android `PairingProtocol.kt`；当前只允许 loopback 配对，未宣称 Wi-Fi TLS 配对完成。
- 会话上下文新增确定性摘要与长度裁剪，聊天任务现在把会话历史和本地记忆传给 provider，并记录已使用的记忆。
- 本轮最终验收仍属于代码/协议/模拟器/构建验收；GitHub Actions 将由最新推送触发，不能提前视为远端 CI 已通过。
- 明确未完成：服务端/Android 深度拆分、完整 Web/Android 现实玩法 UI、Wi-Fi TLS 配对、签名 2.0 APK、ARCore、GPS、PTT、通知/小组件实机闭环。

## 13. 批次 A：统一伴侣摘要与双端现实入口（2026-09-22）

- 新增 `server/companion-summary.js`，把任务、Attention、Mote、设备健康、现实探索、AI Provider、记忆和自治安全状态投影成脱敏 `version=1` 摘要。
- 新增 `GET /api/companion/summary`，使用 revision/snapshot ETag；未变化时返回 `304`，不触发 Web 解析和重绘。
- Web 工作台消费统一摘要，展示 Provider 能力与探针结果；现实事件卡片支持直接发起遭遇和收集动作，并展示 Mote 等级、XP、库存数量。
- Android 新增 `CompanionSummary.kt` 与单元测试；`MainActivity` 和 `MoteWidgetProvider` 使用统一摘要字段，保留 Room/离线镜像和旧协议兼容。
- 本批最终验证：Node 全量 **93/93**；Android `:app:testDebugUnitTest :app:assembleDebug` 返回 `BUILD SUCCESSFUL`；性能预算 `elapsedMs=0.718`、`fullBytes=1694`、`summaryBytes=48`、`snapshotCacheHits=10000`、`broadcastsAfterBurst=1`；敏感扫描、语法检查和 `git diff --check` 通过。
- 边界：本批仍不宣称 Wi-Fi TLS、签名发布、ARCore/GPS/PTT 或实体机通知/小组件验收完成。

## 14. 批次 B：TLS 配对与侧载发布底座（已完成）

- 新增 `server/tls-config.js`：证书/私钥成对加载、证书 SHA-256 指纹和 `ws/wss` 传输描述；服务端在配置 TLS 时自动切换 HTTPS/WSS。
- 非回环绑定节点没有 TLS 时拒绝启动远程配对；远程 claim 必须来自加密连接；`PHONEBRIDGE_PAIRING_HOST` 用于返回手机可访问的局域网地址。
- 新增 `GET /api/diagnostics/export`，只输出 readiness、诊断、持久化和统一伴侣摘要，测试确认不包含访问令牌。
- Android `PairingOffer` 增加 `wss`/指纹安全校验；版本升级到 `versionCode 2`、`versionName 2.0.0`；新增 `scripts/print_release_hash.ps1` 和发布/回滚说明。
- 批次 B 验证：Node **96/96**；Android `:app:testDebugUnitTest :app:assembleDebug` 返回 `BUILD SUCCESSFUL`；性能预算 `elapsedMs=0.706`、`fullBytes=1694`、`summaryBytes=48`、`snapshotCacheHits=10000`、`broadcastsAfterBurst=1`；敏感扫描、语法检查和 `git diff --check` 通过。
- Debug APK：`android/app/build/outputs/apk/debug/app-debug.apk`，90,773,754 bytes，SHA-256 `8C2FD0D836A1084E6C393F1148DE74A69190B4CF229EF891BE683B2B1CCC9B50`。
- 当前仍未使用正式签名 keystore，也未进行真实局域网 TLS、手机配对、ARCore/GPS/PTT 和长时间运行验收。

## 15. 批次 C：现实锚定与实体机首轮验收（2026-09-22）

- 新增 Android `RealityAnchor.kt`：统一 `RealityAnchorProvider`、Canvas/传感器投影、ARCore 能力抽象和热量/帧率降级选择；`RealityLensView` 只消费统一锚点结果。
- 新增 `RealityLocationPolicy.kt`：位置权限关闭、定位关闭、无样本、模拟位置、过期样本和低精度样本统一回退到 `CAMERA_ONLY`；合格位置只转换为粗区域，不保存连续轨迹。
- 修正统一伴侣摘要健康状态映射：兼容 Android 上报的 `bridge/node=online`，避免 App 顶部在线而摘要误报离线；新增 Node 回归测试。
- 设备连接：`192.168.101.68:43003`，型号 `Xperia XZ2`，Android `15`，ADB server 使用独立端口 `5038`；通过 `adb reverse tcp:9503 tcp:9503` 连接当前开发节点。
- 实机证据：最终 APK 安装成功，`versionCode=2`、`versionName=2.0.0`，大小 `91,221,325` bytes，SHA-256 `7A32DC1F0A73CDD4A2C5F5C0191FB09D95E5F6132E92A373648E67CA11093C80`；启动进程存活、无 App `FATAL EXCEPTION`；摄像头启动后 UI 显示 `眼睛开启 · 在线` 与 `画面 10fps`，服务端 `/frame` 返回 HTTP 200（约 46KB），遥测电量 65%、温度 28°C；停止后回到 `CAM OFF/0fps`；现实镜头显示真实预览与 Canvas 叠加并可正常退出。
- 本轮仍未宣称完成：GPS 权限真实授权与位置采样、ARCore 真平面锚定、三类现实线索点击奖励、文本聊天、PTT、通知/小组件、断线恢复和长时间温度/电量回归。当前 `ArCoreAnchorProvider` 保持无外部依赖的能力抽象，默认使用 Canvas 回退。

## 16. 批次 C 收尾：现实协议修复与实体机回归（2026-09-22）

- 修复现实线索协议缺口：Android 旧的 `place` 节点统一映射为服务端协议的 `location`；线索事件改为稳定的 `reality-lens:<coarse-region-or-camera>:<clue>` eventId，离线重试不会重复奖励。服务端同时保留旧别名兼容，Node 回归覆盖别名幂等。
- 新增 Android `RealityLocationCoordinator` 与 `RealityLocationSampler`：进入现实镜头时按需请求 `ACCESS_COARSE_LOCATION`，只读取最近粗粒度样本；权限拒绝、定位关闭、模拟位置、过期、低精度和无 fix 都回退 `CAMERA_ONLY`。显示/传输只允许 `cell:*`，不持久化精确坐标或轨迹。
- 实机设备：`192.168.101.68:43003` / Xperia XZ2 / Android 15 / ADB server 5038；新 APK 安装成功，SHA-256 `A15BA0E4D8BB33532C3F38A6CF4E552521934452D07268056C00EC59D64F3094`，大小 `91,224,726` bytes。
- 实机现实镜头：按需位置权限弹窗正常；真实摄像头画面、Canvas Mote、地点/物体/光线三类线索均点击成功；服务端 `mote.state` 实际从 6 个初始形态解锁 `ember_sprig`，稳定 eventId 为 `reality-lens:camera:location/object/light`，重复线索不增加事件。
- 实机文本/语音：发送 `ping` 得到本地离线回退回复；PTT 录音开始/停止 HTTP 均返回 200，服务端收到 28 个音频块并写入 `44,800 bytes`，无识别文本时安全返回“没有听清”，无 App 崩溃。
- 实机断线/恢复：停止节点后 UI 显示“重连中 / 链路离线”；重启节点后自动恢复，服务端 `clients=1`、`outboxPending=0`。通知频道 `mote_resident`、常驻通知的“抚摸/换眼/回复”动作和 `MoteWidgetProvider` 已由系统注册信息确认。
- 实机稳定性：相机连续观测约 30 秒采样 3 次，进程 PID 保持 `16962`，电池温度 `39.5°C`、电量 `50%` 稳定；服务端 frame 计数达到 `5036`、camera=true、bridge=online；停止后 UI 回到 `CAM OFF / 0fps`，未发现 `FATAL EXCEPTION`。
- 当前仍未宣称：ARCore 真平面锚定（设备走 Canvas 回退）、真实 GPS fix（本轮无可用最近位置样本，已验证安全回退）、正式签名 APK、完整小组件桌面视觉布局和长时间数小时运行。
- 本轮收尾验证：Node `node --check server/index.js` 与 `node --test server/*.test.js` **98/98**；性能预算 `elapsedMs=1.149`、`fullBytes=1694`、`summaryBytes=48`、`snapshotCacheHits=10000`、`broadcastsAfterBurst=1`；敏感扫描通过；Android `android\\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` 返回 `BUILD SUCCESSFUL`；`git diff --check` 通过。Git 的 LF/CRLF 提示不属于差异错误。

## 17. 沉浸式入口批次 1（2026-09-22）

- 新增 `ImmersiveEntryPolicy`：首次启动默认进入 Companion 舞台；只有上次停留在 Reality 且相机权限仍有效时才恢复现实镜头；启动不主动申请危险权限。
- `MainActivity` 进入沉浸模式时隐藏系统栏、工作台面板和 `cockpitDeck` 底栏，仅保留 Mote 舞台、退出、轻量工具手柄、文本输入和 PTT；退出后恢复工作台和系统栏。
- `BridgeService` 使用 `ForegroundServiceTypePolicy` 按实际相机/麦克风状态选择 `dataSync`、`camera`、`microphone` 类型；Manifest 补充 `FOREGROUND_SERVICE_DATA_SYNC`，无权限冷启动不再把相机/麦克风类型硬编码到前台服务。
- Android 单元测试覆盖首次入口、现实镜头恢复、未知持久化值回退、前台服务类型选择；`android\\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` **BUILD SUCCESSFUL**。
- Xperia XZ2 实机（USB 序列号 `QV7017NH1F`，与 `192.168.101.68:41253` 为同一台设备）安装并冷启动通过：相机/麦克风均明确为未授权，进程存活，无 `FATAL EXCEPTION` 或权限崩溃；UIAutomator 确认沉浸退出、工具手柄和文本输入可见，`cockpitDeck` 不再出现在层级树；`dumpsys window` 确认 status bar `visible=false`。本次只验证冷启动入口，未把完整聊天、PTT、现实线索和长时间运行重新计入本批。
- 网络 ADB `192.168.101.68:41253` 在安装期间短暂 offline，实机证据使用同机 USB 通道完成；旁边的 Samsung 设备未使用。

## 18. 统一伙伴状态与任务中枢批次 2（2026-09-22）

- 新增 Android `CompanionSessionRepository`：以同一 `CompanionSnapshot` 汇总 CompanionSummary、timeline 的任务/聊天/Attention/Mote/健康/自治投影和同步状态；保留服务端为真相源，客户端只做幂等投影和离线镜像。
- 新增 `ImmersiveShellCoordinator`：管理 Companion/Reality 表面、抽屉开合、返回键层级和 `phonebridge://task|attention|chat` 深链；任务动作协议只接受 `start|pause|continue|retry|cancel|archive`，每次带 idempotency key。
- `MainActivity` 已将 timeline 快照、旧版 workspace 事件、节点在线/离线/重连和 Room 本地任务/提醒镜像接入统一投影；沉浸工具手柄显示连接、Provider、当前任务、待确认和最近结果。
- Web 工作台把统计、Attention、任务、审批和 Mote 图鉴拆为独立 keyed DOM 区块；相同签名跳过更新，revision 变化也只替换受影响区块，任务详情节点和当前选择保持不重建。
- TDD 先行证据：新增 `CompanionSessionRepositoryTest`、`ImmersiveShellCoordinatorTest`，先验证 unresolved red，再实现后 targeted `:app:testDebugUnitTest` 通过。
- 当前边界：Android 任务动作仍以 Web 任务中枢为完整操作入口，沉浸入口优先展示状态/待确认；本批不新增任意自动执行权限，也不改变服务端硬禁止策略。

## 19. Mote 关系成长与现实探索批次 3（2026-09-22）

- 新增 `server/mote-growth.js`：服务端持久化探索 XP、等级、每日地点/物体/光线三类线索、重复事件集合和 `field-focus` 30 分钟增益；跨日会重置当日进度但保留总成长。
- `MoteGrowthStore.validatePayload` 与 `RealityEngine` 统一拒绝精确坐标，只接受 `camera` 或 `cell:<整数>:<整数>`；现实奖励在引擎写状态前完成验证，避免无效请求产生部分奖励。
- 新增 `GET /api/motes/growth`；`/api/motes`、完整快照、工作区快照、`mote.roster` 和 `mote.exploration` 携带相同成长投影；伴侣摘要把成长等级、今日三类线索和当前增益同步给 Web/Android。
- 新增 Android `RealityExplorationCoordinator`：按粗区域过滤/排序事件，过滤过期事件；线索提交使用稳定 eventId，离线进入已有 Room outbox，服务端 Mote 快照和 workspace ACK 都能恢复 pending/discovered 状态。
- 保留旧 `api-*` Mote 图鉴事件兼容路径，但这些旧事件不计入现实成长，避免历史协议迁移时改变奖励语义。
- 本批验收已通过：Node 全量 **106/106**；Android `CompanionSummaryTest`、`RealityExplorationCoordinatorTest` 定向测试以及 `:app:testDebugUnitTest :app:assembleDebug` 均 `BUILD SUCCESSFUL`；性能预算 `elapsedMs=4.125`、`fullBytes=1694`、`summaryBytes=48`、`snapshotCacheHits=10000`、`broadcastsAfterBurst=1`；敏感扫描、`node --check server/index.js` 和 `git diff --check` 通过。
- 本批没有把 ARCore 真平面、真实 GPS fix 或数小时设备运行宣称为完成；仍以 Canvas/粗区域/离线安全回退为准。

## 20. 发布门禁与验收收口批次 4（2026-09-22）

- 新增 `server/release-gates.js`、`server/release-gates.test.js` 和 `scripts/verify_release_gates.ps1`；清单记录版本号、versionCode、分支、commit、APK 大小和 SHA-256。
- `internal-debug` 允许未签名 Debug 侧载，`release` 强制要求外部 keystore 签名；敏感文件路径、主分支、空产物和无效 hash 会被拒绝。
- CI 在 Android Debug 构建后执行 release gate，并保留 Node 全量测试、协议兼容、性能预算、敏感扫描、`git diff --check` 和干净工作树检查。
- 回滚说明见 `docs/superpowers/phonebridge-batch-d-release.md`；运行时仍先用 `scripts/backup_runtime.ps1` 备份，再按需使用 `scripts/restore_runtime.ps1`，不清理用户工作。
- 本批 Debug 产物门禁已通过：`android/app/build/outputs/apk/debug/app-debug.apk`，`versionName=2.0.0`、`versionCode=2`，`91,718,714` bytes，SHA-256 `003D45116C9F759A66177DC3C298BEFB4229B2769E5F4A74A802E3E320B02FB1`；清单标记为 `internal-debug`，未签名。
- 实机回归状态：本次尝试连接目标 `192.168.101.68:41253`，ADB 返回 `10061`（目标端口拒绝），5038 设备列表为空，因此没有安装/启动本批 APK，也没有把旧批次的实机证据重复计入本批。
- 当前边界：正式签名 APK、ARCore 真平面、真实 GPS fix、长时间温度/电量和最终实机回归必须单独取得证据；本交接不把 Debug 构建或模拟器结果冒充为这些验收。

## 21. 全面深化计划：批次 1 已实现（2026-09-22）

- 基线为 `1a4acee`，仍在 `feature/integrated-enhancement`，未修改 `main`，没有使用子 agent。
- `server/mote-growth.js` 已迁移到 v2：按 `Asia/Shanghai` 保存多日线索桶，生成/解析日期化 v2 事件 ID，持久化业务收据和 revision；旧 `daily`、`seenEventIds` 与旧 `reality:`/`reality-lens:` ID 保持兼容。
- 每日每种线索只发一次；离线最多补交 7 天；重复、过期和拒绝均有明确 `businessStatus/reason`，保存异常会回滚本次奖励。
- 新增 `GET /api/reality/progress`、`GET /api/reality/receipts/:eventId`；WebSocket ACK 增加 `businessStatus`、`businessAccepted`、`reason`、`resultRevision`，旧字段保留。
- RealityEngine 事件 ID 日期化；`field-focus` 增益实际作用于现实 XP；装备未拥有拒绝装配；任务未达到完成条件拒绝领奖。
- Android Room workspace 数据库升级到 v4，outbox 增加 lease/sent/business status/reason/result revision；纯逻辑 `OutboxQueue` 覆盖 claim、ACK 超时、指数退避和终态收据。
- Android Activity 只入队并触发唯一 WorkManager，同步 worker 负责发送；现实线索奖励改为 ACK 后写入本地经验，待确认/拒绝使用 Toast 和协调器状态反馈；`0/1` 线索字段兼容。
- 代码验证：Node `node --test server/*.test.js` **112/112**；Android outbox 定向测试、Reality 模型/探索定向测试通过。
- 本批尚未重新执行 Android 全量构建、最新 CI、Xperia 实机、ARCore 真平面、正式 keystore、两小时运行；这些仍是后续验收项。

实现记录：[`docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-1.md`](docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-1.md)。

## 22. 全面深化计划：批次 2 已实现（2026-09-22）

- 基线仍为 `1a4acee`，批次 1 已由提交 `5ed5f6a` 推送；本批继续在 `feature/integrated-enhancement`，未修改 `main`，不使用子 agent。
- 新增 `server/reality-coordinator.js` 和单测，统一现实奖励、Mote 成长收据、现实增益同步与 `/api/reality/progress` 投影；`server/index.js` 只负责接线，精确区域仍在状态变更前拒绝。
- `RevisionSnapshotCache` 增加独立摘要构建器。首屏摘要请求不物化完整快照；`/api/diagnostics` 与诊断导出新增 `snapshotCache.viewBuilds`，可观测摘要/完整快照是否被重复构建。
- Android `CompanionSessionRepository.applyEvents` 批量合并 timeline，`MainActivity` 对一个 `workspace.events` 批次只发布一次投影/UI 更新；旧单事件和 `0/1` 删除字段兼容保留。
- 验证：Node `node --test server/*.test.js` **115/115**；Android `:app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` **BUILD SUCCESSFUL**；`git diff --check` 通过。
- 本批未重新宣称 Xperia 实机、真实 GPS fix、ARCore 真平面、PTT/通知完整回归、正式签名 APK 或两小时运行完成；这些仍需独立证据。

实现记录：[`docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-2.md`](docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-2.md)。

## 23. 全面深化计划：批次 3 已实现（2026-09-22）

- 新增 `server/proactive-policy.js`，默认主动提醒额度为每小时 1 次、每天 6 次；23:00–07:00 静默，支持专注态、即时静音、重复键去重、持久化和 `/api/proactive/explain` 原因说明。抑制的提醒只进入收件箱，不实时广播/朗读。
- `MemoryStore` 增加 `candidate/confirmed` 状态、候选确认、状态过滤和本轮 `remember=false`；自动候选不再自动进入 AI 上下文，旧记忆状态迁移为 confirmed。
- 工作区会话任务记录记忆选择；Android 普通聊天和 AI 空间都有本轮记忆开关。AI 空间增加开始/暂停/继续/重试/取消/归档按钮，复用 `/api/tasks/:id/actions` 和 provider cancel，带幂等键。
- Android 语音前台状态的准备/监听/处理中/播报阶段映射到音频指标，已有播放打断逻辑未改变。
- 验证：Node `node --test server/*.test.js` **120/120**；Android `:app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` **BUILD SUCCESSFUL**；此前第二批摘要懒构建和协调器测试仍纳入全量回归。
- 本批未重新取得 Xperia 实机、ARCore/GPS、正式 keystore 或两小时温度/电量证据。

实现记录：[`docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-3.md`](docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-3.md)。
