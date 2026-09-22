# PhoneBridge 沉浸式个人伙伴实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:executing-plans` 逐任务实现此计划；本计划不使用子 agent。每个批次独立测试、提交并推送到 `feature/integrated-enhancement`。

**目标：** 在现有 2.0 功能之上，完成自适应沉浸入口、统一伙伴状态、任务/AI 中枢、Mote 成长与现实探索闭环，并以真实测试和交接记录收口。

**架构：** 先用纯逻辑策略模块稳定入口，再把 Android 状态协调从 `MainActivity` 下沉到 Repository/Coordinator；服务端继续以 workspace timeline、revision、eventId 和运行时持久化为真相源。现有 XML/Canvas 保留，避免无收益的 UI 框架重写。

**技术栈：** Node.js 内置 HTTP/WS、现有 WorkspaceStore/RuntimePersistence、Kotlin/XML、Room/WorkManager、CameraX、Canvas/传感器、GitHub Actions。

---

### 批次 1：自适应沉浸入口

**文件：**
- 创建：`android/app/src/main/java/com/phonebridge/ImmersiveEntryPolicy.kt`
- 创建：`android/app/src/test/java/com/phonebridge/ImmersiveEntryPolicyTest.kt`
- 修改：`android/app/src/main/java/com/phonebridge/MainActivity.kt`
- 修改：`android/app/src/main/res/layout/activity_main.xml`
- 修改：`README.md`、`HANDOFF.md`

- [ ] 为首次启动、上次伙伴层、上次现实镜头、权限拒绝、节点离线和恢复场景编写失败测试。
- [ ] 实现纯逻辑入口策略，默认返回伙伴层；仅在上次现实镜头且权限有效时返回现实层。
- [ ] 让 Activity 在布局完成后自动进入沉浸层，启动不请求相机/麦克风/定位；触发功能时保持按需权限。
- [ ] 隐藏系统栏并在窗口重新获得焦点时恢复隐藏；保留上滑抽屉、长按设置和返回键分层退出。
- [ ] 增加状态持久化字段和旧状态默认值，确保升级后仍能启动。
- [ ] 运行目标 Kotlin 测试、Android 单测和 Debug 构建，提交 `feat: add adaptive immersive entry`。

### 批次 2：统一伙伴状态与任务中枢

**文件：**
- 创建：`android/app/src/main/java/com/phonebridge/CompanionSessionRepository.kt`
- 创建：`android/app/src/main/java/com/phonebridge/ImmersiveShellCoordinator.kt`
- 创建：对应 Kotlin 单测
- 修改：`MainActivity.kt`、`activity_main.xml`、`server/index.js` 或现有领域模块
- 修改：Web 工作台 HTML/JS、`README.md`、`HANDOFF.md`

- [ ] 先为统一 `CompanionSnapshot`、抽屉动作、任务深链和离线镜像写失败测试。
- [ ] 把任务、Attention、聊天、Mote、健康和 AI 状态投影到同一快照；局部实体变化只更新受影响视图。
- [ ] 将自然语言创建任务接入草稿/确认/执行/结果状态；确认和硬禁止策略继续由服务端裁决。
- [ ] 抽屉展示当前任务、待确认、最近结果、Provider 状态和节点诊断；首屏不展示设置卡片。
- [ ] Web 与 Android 复用同一 timeline/summary 字段，通知和小组件使用任务/提醒深链。
- [ ] 覆盖离线、断线、重复事件、旧字段、任务重试/取消/审计和 Provider 本地回退测试，提交 `feat: unify companion session state`。

### 批次 3：Mote 关系成长与现实探索

**文件：**
- 创建或扩展：`server/reality-events.js`、`server/mote-growth.js` 及测试
- 创建或扩展：`RealityExplorationCoordinator.kt`、`MoteBehaviorEngine` 的输入适配及测试
- 修改：`RealityLensView.kt`、`MainActivity.kt`、Web 现实事件区域、协议 fixture
- 修改：`README.md`、`HANDOFF.md`

- [ ] 先写区域事件、过期、跨区域、重复奖励、库存/经验/等级和每日任务失败测试。
- [ ] 实现离线可运行的区域事件、线索奖励、道具库存、临时增益和 Mote 经验；所有提交按 eventId 幂等。
- [ ] 接入设备时间、粗区域、温度、电量、任务状态和关系等级，确定性地产生行为/提醒/动画提示。
- [ ] 保持 Canvas/传感器回退，ARCore 不可用或性能不足时不改变奖励与协议路径。
- [ ] 覆盖模拟 GPS、模拟相机、权限拒绝、无网恢复、作弊/过期事件和 Android Room/outbox，提交 `feat: complete mote reality growth loop`。

### 批次 4：拆分、性能、发布和验收

**文件：**
- 修改：`server/index.js` 及已存在的服务端领域模块
- 修改：Android Repository/Coordinator、CI workflow、发布脚本
- 修改：`README.md`、`HANDOFF.md`、版本/变更日志

- [ ] 按深模块原则拆出路由、广播、AI、诊断和持久化；保持旧 API 与 WebSocket 兼容。
- [ ] 增加启动耗时、同步延迟、outbox 积压、内存、电量、温度、Provider 延迟和降级指标预算。
- [ ] 增加权限/无网/断线/恢复/升级迁移 UI 自动化和设备模拟器回归；保留真实设备手工清单。
- [ ] 复核签名、版本号、APK 哈希、备份/回滚、敏感扫描和运行时目录排除；没有外部 keystore 时只生成 Debug 证据，不冒充正式发布。
- [ ] 运行 Node 全量、Android 单测、Debug 构建、性能、敏感扫描、`git diff --check`，再进行实体机和 GitHub Actions 验收，提交 `chore: close companion release gates`。

### 总验收

- [ ] 每批提交后检查远端分支与工作树干净。
- [ ] 最终逐项核对沉浸入口、任务/聊天、Mote 成长、现实探索、离线恢复、通知/小组件和安全边界。
- [ ] 对 ARCore 真平面、真实 GPS fix、正式签名、桌面小组件视觉和数小时运行分别标注“已验证/未验证”，不把模拟结果扩大成实机结论。

