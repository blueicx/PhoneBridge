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
