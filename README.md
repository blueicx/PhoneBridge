# Mote / PhoneBridge

把 Xperia XZ2 Premium 变成一只可指挥的“感官同伴”：动画生物 Mote 会随链路、麦克风、任务和电量改变情绪；下方提供指令台、真实任务进度和手机传感器面板。

## 当前能力

- 摄像头推流：JPEG over WebSocket，可在前后摄像头间切换。
- 语音：持续监听、按住说话（PTT 用 HTTP 控制通道，避免隧道丢小包）、电脑 TTS 下发到手机。
- 指令台：`help`、`status`、`tasks`、`ps`、`disk`、`net`、`sysinfo`、`ping host`、`screenshot`、`say 文字`、`camera on/off/front/back`、`listen on/off`。
- 任务进度：服务端真实子进程执行，进度/状态通过 WebSocket 快照推送。
- 传感器：CPU、内存、电池、温度、网络计数、运行时长；亮屏或感官开启时每 2.5 秒上报，空闲灭屏降至 15 秒。
- 宠物系统：喂食、玩耍、抚摸、等级/经验/好感/能量持久化，触摸有反应。
- 常驻模式：前台服务 + Wake Lock + 常驻相机/麦克风声明；熄屏和回到桌面后链路继续工作。
- 保护策略：低电量或高温时自动降低帧率与画质，避免持续感官任务把手机推向过热。
- 灭屏推流：本地预览 Surface 不可用时自动只保留远程分析流；关闭指令后的 2 秒缓冲包不会把状态误标回开启。
- 持久节点：任务、日志、对话、宠物状态和选定 Codex 任务会保存到 `server/runtime-state.json`，节点重启后自动恢复。
- Web 指挥中心：`http://127.0.0.1:9503`，可看画面、任务、日志、传感器并发指令。
- Codex 面板：列出最近 Codex 任务并可选定；读取 CC Switch 提供方/模型目录，可在 App 内切换模型。
- 选定任务档案：读取真实 Codex rollout 记录，展示状态、活动时间、轮次、token 用量和最近思考/回复时间线。
- 真实对话：Codex 面板可直接和当前模型对话，回复会显示在气泡和日志中。
- 对话面板：独立气泡聊天页，本地保留最近 120 条；支持键盘、语音输入和流式回复。
- 流式回应：模型输出会以增量事件推送到手机，气泡和 Mote 动画实时变化。
- 记忆系统：记录连续陪伴天数、总互动次数、任务成败，以及长时间未陪伴时的好感衰减。
- 桌面小组件与常驻通知：小组件显示等级/情绪/能量/好感，支持喂食/监听；通知提供喂食、监听、换眼等快捷控制。
- 小组件实时状态：显示在线/离线、帧率、电量和活动任务数。
- 安全访问：节点首次启动会生成 `server/access.token`；所有 API、画面、音频和 WebSocket 都需要令牌。
- 对话记忆：Mote 对话携带人设和最近 16 轮上下文，能围绕当前感官状态继续交流。
- 长期记忆：聊天面板可添加/查看/清空事实与偏好；每次对话会注入最多 20 条记忆。输入“记住：……”即可保存。
- 记忆召回：每条记忆有重要性、使用次数和召回时间；对话会按话题相关性、重要性和新鲜度挑选最相关的记忆。
- 一体化任务中枢：任务支持待处理、运行中、暂停、需关注、完成、失败、取消和归档；保留结果、Attention、ActionRun 与脱敏审计记录。
- 个人自治策略：`/api/autonomy` 默认只允许注册表中的只读工具；可编辑白名单，未注册工具、Shell、删除、凭据、发布和不可逆工具始终拒绝；支持过期和 Emergency Stop。
- Mote 图鉴：6 个初始形态 + 焰芽、棱光蝶、苔龟、星鸦 4 个探索形态；服务端持久化当前形态、解锁状态和地点/物体/光线碎片，`eventId` 幂等。
- 统一行为提示：Android 的离线交互、通知/小组件入口和 Canvas 主视图/现实镜头共享 `MoteProfile` 与确定性 `MoteBehaviorEngine`。
- 可靠任务动作：`POST /api/tasks/:id/actions` 支持幂等启动、暂停、继续、重试、取消、归档；`GET /api/tasks/:id/audit` 返回任务审计时间线，列表支持 `state/source/limit` 筛选。
- 可恢复工作区同步：事件带服务端 `revision`，`GET /api/workspace/events?since=N` 获取增量；WebSocket ACK 明确 `accepted/duplicate`，Android 记录游标并在网络恢复时通过 WorkManager 冲刷 outbox。
- 自治租约与参数约束：策略支持 `revision`、`usesRemaining`；注册工具可声明 required/allowedKeys/types/maxStringLength 参数约束。
- Mote 行为接口：`GET /api/motes/behavior` 返回版本化动作、注视、光环、粒子和语音模式提示。
- 任务执行队列：会话任务使用单并发 `TaskRunner`，支持排队、暂停、继续、取消、瞬时失败重试和运行进度审计；急停会取消活动任务。
- 自治审批闭环：`/api/autonomy/approvals` 为受限工具生成一次性确认，批准后只能消费一次，过期、重放和硬禁止调用继续拒绝。
- 同步恢复：`/api/workspace/events?since=N` 返回 delta/snapshot 模式、起止 revision 和 `resetRequired`，事件保留窗口不足时安全回退全量同步。
- Mote 关系任务：`/api/motes/relationship`、`/api/motes/quests` 提供幂等互动经验、等级和陪伴任务；Android 识别审批、关系和任务事件。
- GitHub Actions：`.github/workflows/ci.yml` 自动执行 Node 服务端测试、Android 单元测试/Lint/Debug 构建、发布门禁和差异空白检查；不运行 ADB 或上传运行时数据。
- 加载性能：`GET /api/state?view=summary` 返回首屏所需的轻量投影并支持 ETag；完整快照按 revision 缓存，Web 工作台采用帧合并更新，Android 同步按 revision/eventId 去重。
- 任务运行指标：任务公开摘要包含 runner attempt、retryCount、queuePosition、lastError 和 lastTransitionAt，便于 Web/Android 共用审计信息。
- 工作台加载优化：摘要轮询保存 ETag，服务端返回 `304` 时浏览器跳过解析和 DOM 重绘；任务状态统计、筛选、详情、结果和最近审计按需展示。
- Android 断线恢复：启动时恢复 workspace revision，事件 revision 出现间隙时只触发一次 snapshot，避免恢复阶段重复请求；Mote reminder strength 使用浮点值并作用于 Canvas 动画。
- CI 依赖闭环：GitHub Actions 在 Node 测试前执行 `npm ci --prefix server` 并缓存 `server/package-lock.json`，保证干净 runner 能加载 WebSocket 依赖。
- 统一时间线与状态投影：`GET /api/workspace/timeline` 统一输出 task、chat、attention、mote、health、autonomy 事件，支持 canonical `entity/createdAt`（兼容 `entityType/timestamp`）、monotonic revision、eventId 幂等、实体版本、游标分页、delta/snapshot 恢复与删除操作，支持 ETag/304；WebSocket 增量使用 `workspace.timeline`。
- 可插拔 AI 提供方与本地离线回退：`GET /api/ai/providers`、`GET/PATCH /api/ai/settings`、`POST /api/ai/providers/:id/probe`；支持 Codex、OpenAI-compatible、Gemini-compatible 与本地离线规则引擎；显式 provider 已接入聊天路径，失败只回退本地，备用联网 provider 不自动调用；凭证与日志全面脱敏。
- 诊断与性能策略：`GET /api/diagnostics` 导出启动耗时、同步延迟、事件积压、设备遥测（电量/内存/温度）、provider 延迟与降级计数；保留 30fps 前台目标与后台降频策略。
- Web 控制台多视图与深链：增加收件箱/进行中/历史任务视图、任务与聊天上下文关联、Attention/通知深链数据属性、增量时间线与诊断摘要更新；304/无关事件跳过重绘。
- Android 统一投影底座：新增 `WorkspaceTimeline.kt`（StateFlow 投影更新、任务卡片协议、深链协议、断线缓存间隙恢复、GPS 占位输入底座）与 `AiProvider.kt`（脱敏配置、离线本地回退解析器），保持既有 Canvas 渲染形态分支不变。

## PhoneBridge 2.0 持续增强

当前分支正在推进 2.0 四批增强。已落地的基础能力包括：

- 开发/测试专用确定性设备模拟器：设置 `PHONEBRIDGE_ENABLE_SIMULATOR=1` 后可使用 `GET/PATCH /api/dev/simulator` 模拟粗区域、线索方向、网络、传感器、电量、温度和帧率；默认关闭。
- AI provider 能力、流式 request、取消、每日输出预算和本地记忆存储；新增 `/api/ai/capabilities`、`POST /api/ai/requests/:id/cancel`、`/api/memories`。
- 现实探索引擎：确定性粗区域事件、过期/跨区域校验、遭遇、库存、合成、装备、家园、任务和幂等奖励；新增 `/api/reality/catalog`、`/api/reality/events`、`/api/reality/events/:id/start|resolve`、`/api/reality/crafting`、`/api/reality/loadout`、`/api/reality/habitat`，并提供只由已确认收据派生的 `/api/reality/log`。
- 可选真实 ARCore Reality：兼容设备进入现实镜头时自动尝试标准 ARCore Session，Google 系统安装只请求一次；支持平面命中放置 Mote 和 Anchor 跟踪投影。CameraX 与 ARCore 始终互斥；不支持/安装失败回退 CameraX + Canvas，低帧率或 40°C 热保护回退无相机 Canvas。Android Session 不持久化锚点或相机图像，远程画面仍需原有显式 opt-in，默认关闭并限制为低分辨率异步帧。该能力尚未取得本轮兼容设备实机验收证据。
- Mote 图鉴扩展为 20 个形态，新增潮獭、月鹿、岩鼹、风貂、雷雀、雪兔、花灵、晶蜥、沙狐和影蛾；Android 已加入协议解析和配置回退。
- 安全扫码配对：认证 Web 可调用 `POST /api/pairing/start` 获取五分钟有效二维码；远程配对要求明确的手机可达地址、TLS 和 X.509 DER 证书 SHA-256 指纹。Android 用 CameraX/ZXing 本机读取二维码亮度数据，经 HTTPS 一次性领取令牌并保存到 Keystore；失败时保留原连接配置。固定环境令牌、不可轮换的 `PHONEBRIDGE_TOKEN` 与不安全远程 claim 会在配对前被拒绝。
- 运行时持久化已升级到 schema v3，兼容迁移 v2 信封和旧裸 JSON；会话上下文会裁剪长历史并生成确定性摘要。
- 批次 A 新增统一脱敏伴侣摘要：`GET /api/companion/summary`，支持 ETag/304；Web 工作台展示 Mote、任务、提醒、现实探索、Provider、记忆和自治状态，现实事件可直接发起遭遇或收集。
- Android 新增 `CompanionSummary` 协议模型，主界面和 Mote 小组件读取同一份摘要字段，断线时继续使用本地镜像。
- Android 当前版本为 `2.2.0`（`versionCode 4`，内部 Debug 候选）；已加入可选 HTTPS/WSS、版本化二维码配对载荷、证书指纹 pin、实际 APK 版本/签名门禁和脱敏诊断导出，正式签名仍需外部 keystore 与离线恢复介质。
- Android 探索日志底座合并 `/api/reality/log` 分页收据与本机 `mote.exploration` outbox：accepted/duplicate ACK 仍显示“待确认奖励”，只有服务端 confirmed receipt 才显示经验/道具；拒绝原因只保留安全错误码。缓存最多 500 条，游标仅接受 base64url，`progress` 隐私 revision 变化会清缓存并从第一页重取；离线恢复继续使用唯一 Workspace outbox worker。日志抽屉详情与 Reality 跳转仍待 C3 接入，本轮未做手机验收。

上述 2.0 能力已加入统一双端摘要入口；Wi-Fi TLS 配对与正式签名发布仍需外部证书/keystore，实机结果按能力逐项记录，不把 Canvas 回退扩大为 ARCore 真平面证据。

批次 C 已加入统一 `RealityAnchorProvider`：默认 Canvas/传感器投影，ARCore 不可用、温度过高或帧率不足时自动回退；`RealityLocationPolicy` 拒绝模拟、过期和低精度位置，只保留粗区域；Android 进入现实镜头时按需请求大致位置权限。`Xperia XZ2 / Android 15` 实机已完成相机推流、服务端 `/frame`、现实镜头 Canvas 叠加与退出、三类线索点击并解锁焰芽、文本离线回退、PTT 录音、断线自动恢复、通知频道和小组件 Provider 注册，以及约 30 秒相机稳定性观测。当前仍明确未宣称 ARCore 真平面锚定、真实 GPS fix、正式签名发布和长期运行闭环。

## 最新可靠性补强

- ARCore 热保护只合并当前读数和 10 秒内的温度样本，避免陈旧遥测使保护永久锁定；解除热锁要求低于 38°C 的样本连续、间隔不超过 5 秒并持续一分钟。
- AR Session 关闭时，即使 pause 失败也会继续 close 并进入 CLOSED；沉浸模式硬件返回键统一交给现有导航回调，确保现实镜头先执行相机释放与界面恢复。
- 探索日志 `/api/reality/log` 从 Reality/MoteGrowth 已确认收据即时投影，合并重复 `eventId` 并按时间游标分页；只暴露粗区域、线索类型、固定观察语句和实际奖励。旧版安全线索 ID 可读；清除 `progress` 后底层收据一并删除，不能通过日志重新生成。

## 启动

APK：

```text
F:\CodexApps\PhoneBridge\android\app\build\outputs\apk\debug\app-debug.apk
```

启动本机节点（当前固定隔离端口 9503，避免占用你的其他任务）：

```powershell
& 'C:\Users\blueice\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' F:\CodexApps\PhoneBridge\server\start_detached.py
```

USB 快速连接：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -P 5038 reverse tcp:9503 tcp:9503
```

App 节点地址填 `ws://127.0.0.1:9503`。不同 WiFi 时用 Cloudflare Tunnel：

```powershell
F:\CodexApps\PhoneBridge\cloudflared.exe tunnel --url http://127.0.0.1:9503 --no-autoupdate
```

把日志里的 `https://xxx.trycloudflare.com` 改成 `wss://xxx.trycloudflare.com` 填进 App。

## 接口

- 状态快照：`GET /api/state`
  - 首屏摘要：`GET /api/state?view=summary`；支持 `ETag`/`If-None-Match`，未变化时返回 `304`。
- 执行命令：`POST /api/command {"text":"ps"}`
- 手机控制：`POST /api/device {"action":"camera_front"}`
- 模型切换：`POST /api/codex/select_model {"providerId":"...","model":"..."}`
- Codex 选任务：`POST /api/codex/select_task {"id":"..."}`
- 个人自治：`GET/PATCH /api/autonomy`
- Mote 图鉴：`GET /api/motes`、`PATCH /api/motes/active {"id":"mote"}`、`PATCH /api/motes/exploration {"targetId":"ember_sprig"}`、`POST /api/motes/exploration/clues {"eventId":"...","clueType":"location|object|light"}`
- 任务动作：`POST /api/tasks/:id/actions {"action":"start|pause|continue|retry|cancel|archive","idempotencyKey":"..."}`、`GET /api/tasks/:id/audit`、`GET /api/tasks?state=running`
- 工作区增量事件：`GET /api/workspace/events?since=REVISION`
- Mote 行为：`GET /api/motes/behavior?taskState=running&deviceHealth=degraded`
- 自治审批：`GET/POST /api/autonomy/approvals`、`POST /api/autonomy/approvals/:id/approve`、`POST /api/autonomy/approvals/:id/invoke`
- Mote 关系/任务：`GET/POST /api/motes/relationship`、`GET/POST /api/motes/quests`、`POST /api/motes/quests/:id/claim`
- 模型对话：`POST /api/chat {"text":"你好"}`
- 空闲断流：`POST /api/idle-timeout {"minutes":5}`，范围 1–120 分钟；空闲后会自动关闭摄像头和持续监听。
- Web 指挥中心提供 1/3/5/10/30 分钟空闲断流选择器；手机离线时设备指令会被拒绝并记录日志。
- 统一工作区时间线：`GET /api/workspace/timeline?cursor=REVISION&limit=50&includeSnapshot=true`；支持 ETag/304。
- 诊断与运行指标：`GET /api/diagnostics`；提供启动耗时、同步延迟、积压计数、设备遥测、provider 延迟和降级计数。
- 可插拔 AI 适配器与设置：`GET /api/ai/providers`、`GET /api/ai/settings`、`PATCH /api/ai/settings`、`POST /api/ai/providers/:id/probe`。
- 统一伴侣摘要：`GET /api/companion/summary`；现实探索事件动作：`POST /api/reality/events/:id/start|resolve`。
- 脱敏诊断导出：`GET /api/diagnostics/export`；TLS 配置使用 `PHONEBRIDGE_TLS_KEY`、`PHONEBRIDGE_TLS_CERT` 和可选 `PHONEBRIDGE_PAIRING_HOST`。
- 配对：认证 Web 调用 `POST /api/pairing/start` 获取五分钟一次性 `qrPayload`；手机向其 endpoint 的 `POST /api/pairing/claim` 提交 `id/code/nonce`，远程配对必须使用 HTTPS/WSS、X.509 DER SHA-256 指纹匹配和明确的 LAN 主机地址。
- 备份前落盘：认证 `POST /api/runtime/flush` 将工作区、时间线、Mote、AI 安全设置等运行时状态同步写盘；脚本可通过 `-FlushEndpoint https://<节点>/api/runtime/flush -AccessTokenPath <本机令牌文件>` 调用，令牌仅在内存中用于请求。

App 的“节点”按钮可同时填写节点地址和访问令牌。令牌文件位于 `server/access.token`，请勿把公网地址和令牌一起公开。
- 浏览器令牌失效时会重新提示输入；取消提示不会造成无限弹窗。WebSocket、API、画面和音频都校验同一个令牌。
- 最新画面：`GET /frame`
- 对讲录音：`GET /audio`
- 持续音频：`GET /live_audio`

## 批次 A：可靠性与发布闭环

- `server/runtime-persistence.js` 统一管理 schema v3、兼容 schema v2/旧裸 JSON 迁移、SHA-256 校验、原子替换、有限备份和损坏文件隔离恢复。
- Workspace、时间线、Mote、关系/任务和 provider 安全设置共享同一运行时持久化目录；provider 密钥和令牌不会写入持久化 payload。
- 健康检查：`GET /health/live`、`GET /health/ready`，兼容 `/api/health/liveness` 与 `/api/health/readiness`；`/api/diagnostics` 增加持久化恢复指标。
- 日志通过结构化 JSON 输出并对 token、密码、Authorization、Cookie 和密钥模式脱敏；访问令牌轮换接口只返回轮换结果，不返回新令牌。
- 备份/恢复：`scripts/backup_runtime.ps1`、`scripts/restore_runtime.ps1`；备份只包含可恢复状态文件，排除 token、日志、画面和构建产物。
- CI 现在覆盖 Node、协议回归、性能预算、敏感扫描、Android 单测/Lint/Debug 构建、签名解析与发布门禁、差异检查和干净工作树检查。

批次 A 的设计、迁移和回滚说明见 [`docs/superpowers/phonebridge-batch-a.md`](docs/superpowers/phonebridge-batch-a.md)。最新实体机证据与未验证边界见 `HANDOFF.md` 第 16 节。

### 当前沉浸入口

PhoneBridge Android 首次打开直接进入沉浸式 Mote 舞台，不再弹出相机、麦克风或位置权限；危险权限只在用户主动打开相机、语音或现实镜头时按需申请。上次停留在现实镜头且相机权限仍有效时才会恢复现实镜头，否则安全回到伙伴舞台。沉浸模式隐藏系统栏和工作台底栏，工具、退出、文本聊天和 PTT 通过舞台内的轻量控件访问；前台服务在没有相机/麦克风权限时使用 `dataSync` 类型，避免冷启动崩溃。

批次 2 将 Android 的 timeline、伴侣摘要、任务、Attention、聊天、健康和自治状态汇入 `CompanionSessionRepository`，沉浸工具手柄由 `ImmersiveShellCoordinator` 管理抽屉、返回层级和 `phonebridge://task|attention|chat` 深链。离线时保留 Room 镜像，联网后继续使用 revision/eventId 幂等恢复；沉浸工具手柄显示当前任务、待确认、最近结果、Provider 和连接状态。Web 工作台将统计、提醒、任务、审批和图鉴拆成 keyed DOM 区块，只更新发生变化的区块，不重建整块工作台。

### 批次 3：Mote 成长与现实探索闭环

- `server/mote-growth.js` 持久化 Mote 探索 XP、等级、每日地点/物体/光线三线索进度和 `field-focus` 限时增益；重复 `eventId` 不重复奖励，旧状态可恢复。
- Android `RealityExplorationCoordinator` 过滤过期/跨区域事件，统一 `reality-lens:<coarse-region-or-camera>:<clue>` 协议；离线线索进入已有 Room outbox，收到服务端快照或 ACK 后幂等确认。
- 现实区域严格限制为 `camera` 或 `cell:x:y`，服务端在 RealityEngine 写入奖励前完成校验，拒绝精确坐标、伪造区域和失效事件；旧 `api-*` Mote 图鉴事件继续兼容但不产生探索成长奖励。
- Web/Android 伴侣摘要增加探索等级、XP、今日线索和增益状态；新增 `GET /api/motes/growth`，`/api/motes` 与时间线快照携带同一成长投影。

### 批次 4：发布门禁与回滚

- `server/release-gates.js` 与 `scripts/verify_release_gates.ps1` 生成并校验版本、分支、commit、APK 大小和 SHA-256 清单。
- `internal-debug` 仅表示功能分支 Debug 侧载；`release` 必须显式外部 keystore 签名。门禁拒绝主分支、token/日志/画面等敏感产物和无效 hash。
- CI 在 Android 构建后执行发布门禁；回滚顺序和运行时备份/恢复说明见 [`docs/superpowers/phonebridge-batch-d-release.md`](docs/superpowers/phonebridge-batch-d-release.md)。

## 全面深化批次 1：奖励与离线同步

- Mote 成长状态按 `Asia/Shanghai` 活动日期保存，线索 ID 使用 `reality[-lens]:v2:<date>:<coarse-region>:<clue-type>`；旧事件格式继续兼容。
- 奖励处理写入可重放收据，区分 `accepted`、`duplicate`、`rejected`，带拒绝原因和结果 revision；持久化失败会回滚本次内存奖励。
- 新增 `GET /api/reality/progress` 与 `GET /api/reality/receipts/:eventId`；Workspace WebSocket ACK 增加业务状态字段，同时保留旧 ACK 字段。
- 离线线索最多补交七天，每日每类只奖励一次；Android 先显示暂存，确认后才写入本地 Mote 经验。
- Android outbox 使用单一 WorkManager 调度器、发送租约、ACK 超时、指数退避和 Room v4 恢复字段；Activity 不再与 worker 并行发送。
- Reality 增益实际影响 XP，装备未拥有或任务未完成时不能写入奖励状态。

设计与迁移记录见 [`docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-1.md`](docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-1.md)。

## 全面深化批次 2：模块拆分与加载性能

- 新增 `server/reality-coordinator.js`，把现实线索奖励、成长收据、增益同步和统一进度投影从 `server/index.js` 下沉为可单测模块；旧 API 和 WebSocket ACK 保持兼容。
- `RevisionSnapshotCache` 支持独立轻量摘要构建。首屏 `GET /api/state?view=summary` 不触发完整快照构建；完整快照和摘要的构建次数进入 `/api/diagnostics` 的 `snapshotCache` 指标。
- Android 时间线突发事件在 `CompanionSessionRepository` 内批量合并，WebSocket 一批事件只发布一次公共快照和一次 UI 重绘；旧单事件协议继续可用。
- Node 全量测试 **115/115**；Android `:app:testDebugUnitTest :app:assembleDebug` **BUILD SUCCESSFUL**；`git diff --check` 通过。

实现记录：[`docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-2.md`](docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-2.md)。实体机、GPS/ARCore 和正式签名仍按独立验收边界处理。

## 全面深化批次 3：伙伴与任务空间

- 主动提醒策略独立为 `server/proactive-policy.js`：默认每小时 1 次、每天 6 次，23:00–07:00 静默；支持专注态、立即静音、持久化和 `/api/proactive/explain` 原因查询。
- 记忆支持候选/确认状态、来源、编辑、删除和 `/api/memories/:id/confirm`；`remember=false` 的本轮请求不会读取或写入长期记忆。
- Android AI 空间增加任务开始、暂停、继续、重试、取消、归档和幂等键；流式生成的取消/重试直接复用服务端任务状态与 provider cancel 链路。普通聊天和 AI 空间都可关闭本轮记忆。
- Android 语音状态显示准备、监听、处理中和播报阶段；原有打断播放逻辑继续保留。
- Node 全量 **120/120**；Android `:app:testDebugUnitTest :app:assembleDebug` **BUILD SUCCESSFUL**。

实现记录：[`docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-3.md`](docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-3.md)。实体机、GPS/ARCore 和正式签名仍按独立验收边界处理。

## 全面深化批次 4：角色差异与完整成长

- 服务端保留 12 个通用 Mote 剧情，并新增 20 条逐形态专属剧情；专属剧情只有对应 Mote 激活时才会触发。完成与领奖分离并按来源事件/领奖键幂等；接口为 `GET /api/motes/story` 和 `POST /api/motes/story/:id/claim`，实时事件为 `mote.story`。
- 剧情触发覆盖激活、对话、成功/恢复任务、现实探索、三类线索、关系升级、增益和新形态解锁；Android 图鉴逐只显示专属剧情、完成条件和领奖入口，故事投影进入快照与离线缓存。
- 专属剧情条件只由当前业务事件触发，关系等级剧情要求本事件真实跨过门槛，不会因切换角色而消费累计历史；领奖与关系经验之间使用稳定收据，服务重启可补齐中断奖励，故事收据不受普通交互最近 512 条压缩影响。
- 行为提示继续兼容旧字段，同时增加 `motion`、`visualPreset`、`colors`、`taskAffinity`、`emotionBias` 和 `ability`，角色状态会改变注视、提醒倾向和动作节奏。
- Android 通过 `MoteVisualProfile`/`MoteBodyKind` 统一驱动 20 个形态；14 个探索形态在 `CompanionView` 与 `RealityLensView` 使用各自轮廓、配色、动作和粒子，未知形态安全回退。
- 设计与迁移记录见 [`docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-4.md`](docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-4.md)。实体机、ARCore 真平面、正式签名和长时间运行仍需独立验收。

## 全面深化批次 5：现实探索与锚定

- `server/reality-event-store.js` 负责按粗区域、Asia/Shanghai 日期和 30 分钟时间桶生成确定性现实事件；事件带 `reality:v2` ID、方向、近中远距离等级和过期时间，`RealityEngine` 保持旧接口兼容。
- Android 在线提交优先使用已刷新且未过期的区域事件 ID；服务端校验新事件的区域、活动时间、过期时间和线索类型。无位置、无事件或离线观察保留手动/相机兼容路径，仍受每日限额和离线七天窗口约束。
- `RealityCueAnalyzer` 在本地从低分辨率亮度/边缘信号推导地点、物体、光线提示；原图默认不上传，远程识别必须由本机显式运行时开关启用，并受温度、电量和帧率节流。
- `RealityCaptureController` 统一 CameraX 的 Companion/Reality 所有权；`RealityLensView` 使用附近事件的方向/距离和本地观察标签驱动雷达/Canvas 表现。
- `ArCoreAnchorProvider` 现在只接受真实会话注入的姿态，绝不把 Canvas 结果标成 ARCore；当前未引入 ARCore 依赖，默认明确回退 Canvas。因此不把平面检测、点击放置、真实锚点追踪或 ARCore 实机验收列为完成。

实现与边界记录见 [`docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-5.md`](docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-5.md)。

## 全面深化批次 6：配对、签名与可恢复交付

- 当前 Android 版本为 `2.2.0` / `versionCode 4`（内部 Debug 候选，后续版本已从批次最初的 `2.1.0` 更新）；Release signing 只接受四个 `PHONEBRIDGE_RELEASE_*` 外部环境变量成组注入，缺 keystore 时不冒充正式发布。
- `scripts/verify_release_gates.ps1` 使用实际 APK 的 `aapt dump badging` 和 `apksigner verify --verbose --print-certs`，校验包名 `com.phonebridge`、APK 内部版本、字节数、SHA-256、证书指纹和签名状态；schema v2 manifest 可按需输出到 CI 临时目录。`release` 还必须显式传 `-Signed` 并提供 `PHONEBRIDGE_RELEASE_CERT_SHA256`，不能使用 Android Debug 证书。
- GitHub Actions 上传 Debug APK 与发布 manifest 作为构建产物，源码历史不包含 APK、运行时状态、令牌或签名材料。
- `/api/pairing/start` 返回版本化 `qrPayload`；Android `PairingProtocol` 可解析二维码、生成 claim 字段并转换证书指纹，BridgeLink 在显式指纹下使用 OkHttp certificate pinning，断线重连保留地址/令牌/指纹。
- Android 扫码路径使用 ZXing 仅解析相机亮度平面，不存储或上传扫描画面；配对确认后先完成 HTTPS claim，成功才轮换令牌及连接配置，拒绝/超时不会覆盖旧配置。
- `POST /api/runtime/flush` 提供认证快照落盘。`backup_runtime.ps1` 生成 v3 SHA-256/字节数清单并逐个校验、迁移与净化允许的状态文件；`restore_runtime.ps1 -VerifyOnly` 只验证不写入，实际恢复必须确认节点已停止，失败时自动回滚。备份保留可恢复聊天状态，排除令牌、日志、照片数据、精确坐标、APK 和签名材料；旧 v2 清单仍可验证和迁移。
- `/api/diagnostics/export` 明确声明不含 secrets、原图、精确位置和连续轨迹；正式 keystore 与实机扫码仍待后续验收。
- `scripts/verify_release_gates.ps1` 的签名元数据解析兼容旧版 `Signer #1` 与新版 `V2 Signer` 标签、单行/换行指纹、冒号/空格分隔、CRLF 和 ANSI 着色。Actions run #29（`cf45596`）已通过真实 APK 门禁，并成功上传 Debug APK 与 manifest。
- 后续验收：Node 全量 **142/142**、运行时备份/恢复、签名解析、敏感扫描、性能预算与当前 Debug APK 实际签名门禁通过；Android 单测和 Debug 构建通过。GitHub Actions run #31 首次执行 Lint 揭示 8 个真实代码错误，run #32 增加了完整错误诊断；提交 `11fe2cd` 修复后台录音的二次权限检查与撤权处理、粒子白色 RGB 分量和音频循环缩进。GitHub Actions run #33 全部通过，Android Lint 错误清零并完成 APK artifact 上传。本机 `lintAnalyzeDebug` 曾连续运行超过 6 分钟无进展，未计作通过。无线实机、正式签名和 ARCore 真锚定仍未验收。

本批实现记录见 [`docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-6.md`](docs/superpowers/plans/2026-09-22-phonebridge-deepening-batch-6.md)。正式 keystore、真实 WSS/二维码扫描和 Xperia 实机验收仍待独立证据。

## 全方向深化批次 2：沉浸舞台与家园

- Android 新增纯逻辑 `CompanionStageEngine`，依据时段、能量、任务、观察和最近互动投影舞台光线与休息/观察/专注/互动节奏；提醒或任务气泡五秒后自动消失。
- `CompanionView` 将家园摆件绘制到沉浸舞台并支持点击反馈；摆件仍通过已有 `/api/reality/catalog`、`/api/reality/state` 与 `/api/reality/habitat` 保存，离线时继续显示本机缓存。
- 舞台抽屉可设置单手布局、减弱动画、安静模式和环境音。安静模式保留文字提醒并抑制语音；环境音由本机低音量合成，默认关闭，只在用户启用且舞台可见、Activity resumed 时播放，离开/后台/关闭时释放音频焦点与轨道。
- 验证：Node **142/142**；Android 定向舞台/音频单测通过；完整 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` **BUILD SUCCESSFUL**。无线 ADB 当前不可用，因此家园点击、音量和单手布局仍需实机观感验收。

## 全方向深化批次 3：Mote 角色差异与剧情分支

- 现有 20 个 Mote 均有可区分的待机、触摸、任务和探索提示；关系阶段会改变称呼与动作强度，图鉴可预览动作并回顾最近完成的专属剧情。
- 每条专属剧情提供“继续探索”和“留在家园”两种持久化结局；选择不可变且按故事领奖收据幂等，继续探索额外奖励 3 XP。旧客户端不选择分支时沿用基础奖励与兼容结局。
- 验证：Node **145/145**；Android **121 tests / 0 failures**，`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 全部通过；敏感扫描、Debug APK 门禁、性能预算与 `git diff --check` 通过。无线 ADB 不可用，20 只角色的视觉辨识度和触摸手感仍待实机验收。

## 全方向深化批次 4：连续聊天、语音与记忆

- 文本聊天发送期间可停止；失败可重试或接续，服务端把稳定 `requestId` 传入 provider 并支持取消，Android 丢弃已取消或过期请求的迟到增量。provider 在开始输出前失败时只回退本地规则；已有部分输出或请求取消时不启动第二个回复。
- 连续语音明确呈现识别、生成、播报和失败状态；可打断远端生成及 TTS，失败可重试。普通聊天不混入语音/通知回复。
- 记忆显示来源、确认状态和更新时间；自动提取信息先作为候选，支持确认、编辑、删除和“不再提起”。每轮关闭记忆时，显式“记住”与自动提取都不会写入，候选或被排除记忆不会进入上下文。聊天创建的任务保留会话/消息回链，任务结果仍返回原会话。
- 验证：Node **149/149**；Android **128 tests / 0 failures**，`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 全部通过；敏感扫描、Debug APK 内部门禁、性能预算与 `git diff --check` 通过。未进行手机语音/界面实测，须待 Xperia 可用后验收打断、重试、候选确认和长时间语音会话。

## 全方向深化批次 5：现实探索与奖励恢复闭环

- 现实遭遇贯通“发现 → 当前 Mote 的观察提示 → 用户选择动作 → 遭遇奖励 → 收据回显”。Node 保存事件、动作、物品和经验的幂等收据；多个奖励账本可在进程中断后独立重放。若遭遇收据已落盘，即使短时区域事件后来过期，也能仅据该收据补齐尚未完成的账本；没有既有收据的过期区域事件仍会被拒绝。
- HTTP 与 WebSocket ACK 区分同一 `eventId` 的安全重放和不同事件复用 `origin:sequence`。后者明确返回业务拒绝，Android 不再按成功结算本地成长。新增回归覆盖该冲突和过期后恢复。
- Android 线索记录改为按上海活动日期解锁，兼容旧线索名；用户先选观察方式，再通过持久化 outbox 等待业务 ACK。成功后查询服务端收据并显示道具/经验，拒绝时不记为已发现。AR 锚点可重新放置，追踪丢失时有明确提示；无相机或定位能力时继续提供手动探索。本流程不保存原图或精确轨迹。
- 验证：Node **154/154**；Android JVM **136 项 / 0 失败**，`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 全部通过；敏感扫描、内部 Debug APK 门禁、签名解析测试、性能预算和 `git diff --check` 通过。性能预算结果：`elapsedMs=1.775`、完整快照 `1694 bytes`、摘要 `48 bytes`、缓存命中 `10000`、突发广播 `1`。
- Xperia XZ2 / Android 15 通过 USB 在线（无线地址 `192.168.101.68:39663` 不可达）。使用 `adb install -r` 将内部 Debug 版从 `2.1.0` 升级到 `2.2.0` / code `4`，未清除应用数据；冷启动 `1.492 s`，沉浸舞台进入前台且没有捕获到 Android 崩溃。镜头保持关闭，因此相机/定位线索、遭遇奖励、平面重定位和无线恢复仍待完整实机交互验收；该 APK 是 Debug 内部候选，不是正式签名发布版。

## 全方向深化批次 6：性能基线与可恢复交付

- Xperia 当前实测样本：冷启动 **1.492 s**；舞台 `dumpsys gfxinfo` 共 1225 帧，UI P95 **25 ms**、GPU P95 **19 ms**、Janky frames **0.49%**，达到“冷启动 2 秒内、帧耗时 P95 不超过 33.3 ms”的目标。`dumpsys meminfo` PSS **141821 KB**。采样时电量 **21%**、电池温度 **39.2°C** 且正在充电；这不是两小时稳定性或耗电测试。
- `scripts/test_runtime_backup.ps1` 在隔离临时目录完成备份与恢复故障演练：校验 v3 清单/哈希、VerifyOnly 不写入、替换失败回滚、成功恢复、损坏/缺失/路径穿越/空备份拒绝，以及 v2 迁移。真实运行时目录未被覆盖。
- Pairing/TLS pin、一次性 claim、拒绝错误 nonce、Android 二维码/claim 协议、断线状态与 outbox 恢复已由 Node/Android 自动测试覆盖。实机扫码、无线断线恢复和同步延迟尚未验收：无线 `39663` 不可达，应用相机权限未授权，当前界面处于离线状态。
- 批次 5 GitHub Actions run [36340549528](https://github.com/blueicx/PhoneBridge/actions/runs/36340549528) 全部成功并上传内部 Debug APK artifact；APK 未加入源码历史。此构建不代表正式签名。
- 正式 Release 签名保持关闭：尚无已验证的独立加密密钥恢复副本。待设备网络/相机权限可用后，补扫码配对、断线恢复、探索全流程和两小时稳定性/热/电量验收；同步延迟需在连接节点后采样。

## 隐私中心与加密恢复

- 新增 Android/服务端统一隐私中心，按长期记忆、聊天/会话/共享交接、任务/审计、Mote 成长/探索分项展示；加密档案按所选类别生成，并由系统文件选择器保存。删除必须逐字确认，并通过 `privacy.deleted` 同步清理 Android Room、outbox 与相关本地缓存。
- 加密包采用 scrypt + AES-256-GCM；节点外网只允许 HTTPS 导出。运行时备份支持 `-Encrypt`，恢复脚本接受 `.pbenc` 并提供 `-VerifyOnly`，解密发生于受限临时目录。
- 排除项：令牌/配对凭据、Provider 密钥、原始照片/画面、精确位置和连续轨迹。共享交接内容归入 conversations 类别，删除时同时清空服务端文件及手机镜像。
- 删除期间会临时阻止新的写操作、取消并等待在途聊天，排空已进入的 HTTP 写请求；清理关联的终态任务内存记录和 Room/outbox 后再确认完成。手机重连先拉取有限的已完成删除收据并清缓存，再恢复 outbox，避免离线错过实时广播。
- Android 本机删除未成功时会暂停 outbox 恢复发送，待下次成功对账服务端删除收据后再同步，避免被删数据重新上传。
- 批次验证见 [`docs/superpowers/plans/2026-09-29-phonebridge-privacy-and-recovery.md`](docs/superpowers/plans/2026-09-29-phonebridge-privacy-and-recovery.md)；本批 CI [run 36599821041](https://github.com/blueicx/PhoneBridge/actions/runs/36599821041) 已通过并上传内部 Debug artifact。手机上的隐私对话框与系统文件选择器尚待点击验收。

## 2026-09-30 批次 B1：可选日常活动

- 新增持久化服务端 `DailyRoutinesStore` 和三种固定活动：专注计时、无需定位/相机权限的散步观察、纯文本睡前回顾。支持开始、暂停、继续、跳过、完成和中断；活动记录可分页恢复，不含签到连胜、逾期惩罚或默认主动提醒。
- `GET /api/routines?cursor=0&limit=20` 返回目录、未结束条目、历史和 `privacyRevision`；`POST /api/routines/:id/events` 按 `eventId` 幂等，校验动作、时间顺序、elapsed 值及状态转换。睡前回顾文本上限 1000 个 Unicode 字符，只在用户完成活动时保存，不写入长期记忆。
- routines 隐私类别现提供准确活动数、加密导出和删除；删除提升类别 revision，缺失/过期 revision 的旧请求会收到可重试 409。写操作复用全局 `beginMutation()`，隐私删除等待已入场请求并拒绝后续写入。
- 当前 B1 只接服务端；Android Room 镜像、离线 outbox 和抽屉入口留在 B3，Web 卡片留在 B4。未请求位置/相机权限，也未运行手机验收。
- 验证：B1 定向 Node **28/28**，服务端全量 Node **203/203**，`node --check` 与 `git diff --check` 通过。GitHub Actions 将在本批推送后对最新 SHA 独立验收。

## 2026-09-30 隐私 revision 与旧客户端迁移

- 服务端按六个隐私类别维护单调 revision；旧审计无法无歧义迁移时，按旧类别逐项保留或清除确认。已确认选择可幂等重试，冲突选择明确拒绝。
- Android Room v5 为 outbox 保存类别、revision 和 quarantine 状态；旧 outbox 默认隔离，未知个人事件 fail-closed。同步需先取得完整六类别 overview，并完成待确认迁移；未完成时只暂停联网发送，不阻塞沉浸舞台。
- 首次 overview 不会把旧 outbox 事件重盖成较新的删除 revision；用户选择后新建的事件预附将确认的 revision，但仍等服务端确认后才恢复发送。隔离数据只读，可由用户主动口令加密导出。
- Node 与 Kotlin 共用 `privacy-overview.json`、`privacy-event-revision.json` fixtures，覆盖旧字段兼容、缺失/过期 revision 和关联任务双类别校验。Room v4→v5 SQLite migration 与本地恢复由 Android emulator CI 实际运行；JVM 单测或仪器测试编译不替代该验证。
- 本机自动验证：Node **195/195**；Android JVM **149/149**，Lint 0 issues，Debug 构建成功，Android instrumentation 测试源码编译成功。GitHub Actions [run 36648683178](https://github.com/blueicx/PhoneBridge/actions/runs/36648683178) 在最新代码 SHA `6a84cbc67930a95bfb504adcfcccdf18a8b30ddb` 的第 3 次尝试全绿：Room v4→v5 emulator migration **7/7**，并通过 Node、Android JVM、Lint、Debug 构建、签名解析、发布门禁、artifact 上传和工作树检查。内部 Debug artifact `phonebridge-debug-6a84cbc67930a95bfb504adcfcccdf18a8b30ddb`（65,370,382 bytes；artifact ZIP SHA-256 `0a5c3fe0f54e2d0fa154499d2f93ea4f989fa4e431877ba935c55ec6f2402950`）。本轮验证未运行本地 ADB、未安装或操作手机；正式 Release 签名与真实设备迁移/扫码仍按交接待验收。

## 2026-09-30 批次 B2：个人目标、AI 草案与确认任务

- `WorkspaceStore` 是目标、里程碑、接受收据和关联普通任务的唯一持久化来源。用户编辑并显式确认步骤后，目标里程碑与普通 Workspace tasks 在同一快照提交；失败回滚，`eventId` 重放返回原收据，不重复建任务。里程碑进度跟随既有任务状态，不维护第二套任务状态机。
- 里程碑将运行、暂停、待确认统一投影为进行中；成功/失败结果在任务归档后仍保留，不会因归档而回退。
- 新增 `/api/goals` CRUD、`POST /api/goals/:id/draft` 和 `/accept`。AI 仅在用户点击请求时调用当前选中的 provider；失败/输出不合规时仅回退本地规则，不切换其他联网 provider。草案不持久化、不创建任务；provider 诊断审计限制为 provider ID、耗时、降级原因、结果状态，不记录目标正文、草案或密钥。
- 删除目标会拒绝活跃任务并事务级联清除目标任务与关联 Attention、ActionRun、审计和时间线；删除 `tasks` 隐私类别则保留目标/里程碑文字并解绑任务。目标任务 ID 使用完整领域索引枚举，不受常规任务列表 200 条投影上限限制；接受接口同时校验 `goals` 与 `tasks` revision，阻止旧 outbox 在任务清理后重建任务。
- 验证：目标/工作区/隐私/API 定向 Node **65/65**，Node 全量 **221/221**；相关 `node --check`、工作区性能预算、`scan_secrets.ps1` 与 `git diff --check` 通过。最终性能预算输出 `elapsedMs=2.203`、完整快照 `1694 bytes`、摘要 `48 bytes`、缓存命中 `10000`、突发广播 `1`。
- B2 源码提交 `32a052d6e3a57a5061e9307ee03307f74c882ead` 的 GitHub Actions [run 36667677629](https://github.com/blueicx/PhoneBridge/actions/runs/36667677629) 全部成功（23m40s），包含 Room emulator migration **7/7**、Lint、Debug 构建、签名解析、发布门禁、artifact 上传及干净工作树检查。内部 Debug artifact `phonebridge-debug-32a052d6e3a57a5061e9307ee03307f74c882ead` 为 **65,370,820 bytes**，ZIP SHA-256 `459dd29e8e9918da3bd1cb9c07f9af7f273e71333ff271fe768313df699a1279`；APK 未进入源码历史。
- 当前仅完成服务端 B2。Android Room 镜像和沉浸抽屉在 B3，Web 目标工作台在 B4；未运行 ADB、未操作手机。后续文档收尾提交的 Actions 状态另行确认。

## 2026-09-30 批次 B3：Android 日常与目标镜像

- Android Room 升级至 v6，持久化日常记录、目标、里程碑及普通任务关联；5→6 migration 保留既有 task 行。routine outbox 经带证书指纹校验的 HTTPS 请求同步，分别呈现待同步、服务端确认和业务拒绝，不把 HTTP 传输成功当业务成功。
- 沉浸工具抽屉新增“日常”和“目标”。专注计时支持暂停/继续/跳过/完成/中断；散步观察不请求定位或相机；睡前回顾是可选纯文本，不写入长期记忆。离线队列依赖此前已同步的完整隐私概览和类别 revision，缺少安全版本时不会盲目发送。
- 目标支持节点创建、显式请求当前 provider 的草案、编辑/删除步骤并逐条确认；只有确认后才创建普通 Workspace task。Room 保存 goal/milestone/task 同一确认结果，任务状态变化回写里程碑；目标任务按 `goals`+`tasks` 两类隐私 revision 分类。
- `WorkspaceClient` 对相同 TLS 指纹复用 OkHttp 客户端和连接池，证书指纹不同则隔离客户端配置。
- 本机验证：Node **223/223**；Android JVM **164/164**；`:app:compileDebugAndroidTestKotlin`、`:app:lintDebug`（0 lint errors）和 `:app:assembleDebug` 全部成功；秘密扫描、Android CI workflow contract、APK signer parser、Debug APK release gate、`git diff --check` 通过。性能预算：`elapsedMs=0.668`、完整快照 `1694 bytes`、摘要 `48 bytes`、缓存命中 `10000`、突发广播 `1`。
- GitHub Actions [run 36688324533](https://github.com/blueicx/PhoneBridge/actions/runs/36688324533) attempt 2 在 SHA `cd4eee96b3e592d6cff662ae0f08e27348279d06` 全绿：Room v5→v6 API 34 emulator instrumentation **10/10**，Node、Android JVM、Lint、Debug 构建、签名解析、发布门禁、artifact 上传、diff whitespace 与干净工作树检查均通过。首轮 CI 揭示迁移测试误用了 `workspace_routines` 名称，已在 `cd4eee9` 修正；attempt 1 遇到 GitHub runner emulator ADB 离线并超时，attempt 2 完整通过。
- 内部 Debug artifact `phonebridge-debug-cd4eee96b3e592d6cff662ae0f08e27348279d06`（artifact ID `11086944240`，65,468,054 bytes；ZIP SHA-256 `73afd3877a2cec78591e8978459827e5eda7836aac6edd7a6f7a95cbe3265790`）仅保存在 GitHub Actions，未加入源码历史。手机未触碰、未安装、未授予权限；实机体验与正式 Release 签名仍待各自门槛。

实现计划：[`docs/superpowers/plans/2026-09-30-phonebridge-reliability-companion-exploration.md`](docs/superpowers/plans/2026-09-30-phonebridge-reliability-companion-exploration.md)。

## 2026-09-30 批次 B4：Web 日常、目标与隐私工作台

- 现有工作台新增日常活动卡片，读取服务端例程目录、活动/历史状态及 revision；支持开始、暂停、继续、跳过、完成和中断。睡前回顾只在完成时提交，错误会显示服务端原因；网络结果不确定时以相同 `eventId` 重试，避免重复记录。活动计时按前台页面时钟展示，不申请定位/相机权限。
- 新增个人目标工作区。AI 步骤草案仅在用户点击后请求当前 provider，显示 provider 与本地回退原因；编辑器确认前不会调用 accept 或创建任务。确认后只创建普通 Workspace task，并打开现有任务详情/审计；里程碑状态仍读取同一任务状态机。草案和补充上下文仅在当前页面内存保留，不记录进 provider 审计。
- 隐私类别勾选项从 `/api/privacy/overview` 动态生成，默认不勾选；`routines`、`goals` 可各自选择加密导出或删除，不再要求手输类别 ID。服务端保留既有类别 ID 与旧客户端兼容。
- Node/Kotlin 共用 routine、goal/draft 与新增 `goal-task-ref.json` fixtures，覆盖未知扩展字段忽略及缺失 task 状态安全回退。Web 脚本在认证页面测试中作语法编译检查，并验证草案调用与确认任务相互独立。
- 本机验证：Node **225/225**；Android JVM **165/165**；Android `lintDebug` 与 `assembleDebug` 成功；`node --check server/index.js`、密钥扫描、性能预算和 `git diff --check` 通过。最终性能预算 `elapsedMs=0.735`、完整快照 `1694 bytes`、摘要 `48 bytes`、缓存命中 `10000`、突发广播 `1`。
- GitHub Actions run [36703474633](https://github.com/blueicx/PhoneBridge/actions/runs/36703474633) attempt 2 已在 B4 源码 SHA `cedb7332ac36286638cc3febd93ff55e0c03b893` 全绿；Room v5→v6 emulator instrumentation **10/10**，Node、Android JVM、Lint、Debug 构建、签名解析、发布门禁、artifact 上传、diff 检查和干净工作树均通过。attempt 1 仅在等待 Android emulator 启动时超时，Room 测试未开始；attempt 2 完整执行成功。
- 内部 Debug artifact `phonebridge-debug-cedb7332ac36286638cc3febd93ff55e0c03b893`（artifact ID `11093506908`，`65,468,196 bytes`；SHA-256 `4342397d4551aa44b7b9d294909a39a8cfd235ca0553efef63be17e517ab25ee`）只保留在 GitHub Actions，未进入源码历史。
- 此批未运行 ADB、未安装或操作手机，也未做浏览器交互验收；UI 实际交互和真机仍待验收。文档收尾提交后的最新 SHA 仍需通过 Actions 后再进入 C1。

实现计划：[`docs/superpowers/plans/2026-09-30-phonebridge-reliability-companion-exploration.md`](docs/superpowers/plans/2026-09-30-phonebridge-reliability-companion-exploration.md)。
