# PhoneBridge 交接文档（Round 57 综合基线 / Round 58 收口）

更新时间：2026-09-08（以证据文件与测试验证时间为准）

## 0. Round 58 当前状态覆盖（2026-09-08）

- GitHub 公有仓库：<https://github.com/blueicx/PhoneBridge>，默认分支为 `main`。
- 最新提交：`6e510cd`。已清除脚本中的硬编码设备 PIN；设备 PIN 仍需在手机端自行更换，严禁重新写入代码或文档。
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

## 5. 当前阻塞点与下一步行动 (Next Steps)

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
