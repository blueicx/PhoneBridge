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
- 执行命令：`POST /api/command {"text":"ps"}`
- 手机控制：`POST /api/device {"action":"camera_front"}`
- 模型切换：`POST /api/codex/select_model {"providerId":"...","model":"..."}`
- Codex 选任务：`POST /api/codex/select_task {"id":"..."}`
- 模型对话：`POST /api/chat {"text":"你好"}`
- 空闲断流：`POST /api/idle-timeout {"minutes":5}`，范围 1–120 分钟；空闲后会自动关闭摄像头和持续监听。
- Web 指挥中心提供 1/3/5/10/30 分钟空闲断流选择器；手机离线时设备指令会被拒绝并记录日志。

App 的“节点”按钮可同时填写节点地址和访问令牌。令牌文件位于 `server/access.token`，请勿把公网地址和令牌一起公开。
- 浏览器令牌失效时会重新提示输入；取消提示不会造成无限弹窗。WebSocket、API、画面和音频都校验同一个令牌。
- 最新画面：`GET /frame`
- 对讲录音：`GET /audio`
- 持续音频：`GET /live_audio`
