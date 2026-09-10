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
- GitHub Actions：`.github/workflows/ci.yml` 自动执行 Node 服务端测试、Android 单元测试和差异空白检查，不运行 ADB 或上传运行时数据。
- 加载性能：`GET /api/state?view=summary` 返回首屏所需的轻量投影并支持 ETag；完整快照按 revision 缓存，Web 工作台采用帧合并更新，Android 同步按 revision/eventId 去重。
- 任务运行指标：任务公开摘要包含 runner attempt、retryCount、queuePosition、lastError 和 lastTransitionAt，便于 Web/Android 共用审计信息。
- 工作台加载优化：摘要轮询保存 ETag，服务端返回 `304` 时浏览器跳过解析和 DOM 重绘；任务状态统计、筛选、详情、结果和最近审计按需展示。
- Android 断线恢复：启动时恢复 workspace revision，事件 revision 出现间隙时只触发一次 snapshot，避免恢复阶段重复请求；Mote reminder strength 使用浮点值并作用于 Canvas 动画。
- CI 依赖闭环：GitHub Actions 在 Node 测试前执行 `npm ci --prefix server` 并缓存 `server/package-lock.json`，保证干净 runner 能加载 WebSocket 依赖。

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
- Mote 关系/任务：`GET/POST /api/motes/relationship`、`GET /api/motes/quests`、`POST /api/motes/quests/:id/claim`
- 模型对话：`POST /api/chat {"text":"你好"}`
- 空闲断流：`POST /api/idle-timeout {"minutes":5}`，范围 1–120 分钟；空闲后会自动关闭摄像头和持续监听。
- Web 指挥中心提供 1/3/5/10/30 分钟空闲断流选择器；手机离线时设备指令会被拒绝并记录日志。

App 的“节点”按钮可同时填写节点地址和访问令牌。令牌文件位于 `server/access.token`，请勿把公网地址和令牌一起公开。
- 浏览器令牌失效时会重新提示输入；取消提示不会造成无限弹窗。WebSocket、API、画面和音频都校验同一个令牌。
- 最新画面：`GET /frame`
- 对讲录音：`GET /audio`
- 持续音频：`GET /live_audio`
