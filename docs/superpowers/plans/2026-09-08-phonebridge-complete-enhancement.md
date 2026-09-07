# PhoneBridge 完整增强实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在现有一体化增强版本上完成任务可靠性、离线同步、自治安全和 Mote 行为统一四个方向，并保持单用户、单节点、无手机实机验收范围。

**架构：** 服务端以 `WorkspaceStore` 作为任务、事件、策略和审计真相源；事件采用 eventId、origin/sequence、revision 和明确 ACK 语义。Android 以 Room/outbox 镜像服务端状态，WorkManager 负责恢复同步；Web、Android 和服务端共享 Mote profile/behavior wire model。

**技术栈：** Node.js 内置测试、HTTP/WebSocket、JSONL 持久化、Kotlin Android、Room、WorkManager、现有 Canvas 渲染体系。

---

### 任务 1：任务状态机与任务审计

**文件：**
- 修改：`server/workspace-core.js`
- 修改：`server/index.js`
- 测试：`server/workspace-core.test.js`、`server/workspace-api.test.js`

- [ ] 为合法状态转换、非法跳转、幂等 retry/cancel、任务审计关联和任务详情接口编写失败测试。
- [ ] 实现显式状态转换表、动作幂等键、重试上限、任务审计记录和 ActionRun/Attention 关联。
- [ ] 增加任务查询过滤、详情 payload 和统一 `workspace.task` / `task.action` / `task.audit` 广播。
- [ ] 运行 `node --test server/*.test.js`，确认任务相关测试全部通过。
- [ ] 提交：`feat: harden task state machine and audit trail`。

### 任务 2：自治策略安全化

**文件：**
- 修改：`server/workspace-core.js`
- 修改：`server/index.js`
- 修改：`android/app/src/main/java/com/phonebridge/WorkspaceProtocol.kt`
- 修改：`android/app/src/main/java/com/phonebridge/WorkspaceRepository.kt`
- 测试：`server/workspace-core.test.js`、`server/workspace-api.test.js`、`android/app/src/test/java/com/phonebridge/WorkspaceProtocolTest.kt`

- [ ] 为参数约束、策略 revision、过期租约、一次性授权、旧 revision 拒绝和急停广播编写失败测试。
- [ ] 实现白名单工具的参数 schema/约束、策略版本检查、租约/一次性授权和不可绕过的硬禁止检查。
- [ ] 让策略变更、授权理由、过期和急停都写入脱敏审计并同步给 Android。
- [ ] 运行 Node 测试和 `:app:testDebugUnitTest`，确认策略兼容与安全边界通过。
- [ ] 提交：`feat: add versioned autonomy leases and argument guards`。

### 任务 3：事件恢复、ACK 和 Android outbox

**文件：**
- 修改：`server/workspace-core.js`
- 修改：`server/index.js`
- 修改：`android/app/src/main/java/com/phonebridge/WorkspaceProtocol.kt`
- 修改：`android/app/src/main/java/com/phonebridge/WorkspaceEntities.kt`
- 修改：`android/app/src/main/java/com/phonebridge/WorkspaceRepository.kt`
- 修改：`android/app/src/main/java/com/phonebridge/BridgeLink.kt`
- 修改：`android/app/src/main/java/com/phonebridge/MainActivity.kt`
- 测试：对应 Node/Kotlin 协议、Room 和 outbox 测试

- [ ] 为 applied/duplicate/rejected ACK、revision 缺口、最大重试、永久失败、断线重连和旧快照冲突编写失败测试。
- [ ] 实现恢复握手、服务端事件 revision、缺口拉取、明确 ACK 状态、最大重试和人工重试入口。
- [ ] 增加 WorkManager 网络恢复冲刷；Room 保存同步游标、失败原因和最后一次服务端 revision。
- [ ] 保证 eventId 和 origin/sequence 双重幂等，禁止旧快照覆盖较新状态。
- [ ] 运行 Node 测试、`.\gradlew.bat :app:testDebugUnitTest` 和协议兼容测试。
- [ ] 提交：`feat: make workspace sync resumable and idempotent`。

### 任务 4：Mote 行为 wire model 与 Web/Android 统一

**文件：**
- 修改：`server/mote-profiles.js`
- 修改：`server/index.js`
- 修改：`android/app/src/main/java/com/phonebridge/MoteProfile.kt`
- 修改：`android/app/src/main/java/com/phonebridge/MoteBehaviorEngine.kt`
- 修改：`android/app/src/main/java/com/phonebridge/CompanionView.kt`
- 修改：`android/app/src/main/java/com/phonebridge/RealityLensView.kt`
- 修改：`android/app/src/main/java/com/phonebridge/MoteWidgetProvider.kt`
- 修改：`android/app/src/main/java/com/phonebridge/MainActivity.kt`
- 测试：`server/mote-profiles.test.js`、新增/扩展 Kotlin Mote 行为测试

- [ ] 为行为提示版本、任务/健康/互动/探索输入映射、情绪衰减、未知字段回退和 Web payload 编写失败测试。
- [ ] 实现服务端 `mote.behavior` wire payload，Android 解析同一字段并对未知版本安全回退。
- [ ] 让 Web、Canvas、通知和小组件消费统一动作强度、注视、光环、粒子和提醒倾向。
- [ ] 增加探索历史、重复线索反馈和解锁状态事件，不改变现有 10 个 Mote 的兼容 ID。
- [ ] 运行 Node 测试和 Android 单元测试。
- [ ] 提交：`feat: unify mote behavior across clients`。

### 任务 5：工作台与文档收尾

**文件：**
- 修改：`server/index.js`
- 修改：`android/app/src/main/java/com/phonebridge/MainActivity.kt`
- 修改：`README.md`
- 修改：`HANDOFF.md`
- 测试：Node 全量、Android 单元测试和构建

- [ ] Web 工作台增加任务筛选/详情/审计、自治授权理由、同步状态和 Mote 行为状态。
- [ ] Android 增加任务摘要、同步错误/人工重试和策略过期提示，保持离线可读。
- [ ] 更新 README/HANDOFF，明确 API、事件、版本迁移、手机验收延期和已验证边界。
- [ ] 运行 `node --test` 全量、`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`、`git diff --check`、敏感模式扫描和 `git status`。
- [ ] 提交：`docs: document complete enhancement handoff`。

### 验收清单

- [ ] 不执行 ADB、实体手机连接或手机 UI 验收。
- [ ] 服务端全量测试 0 失败。
- [ ] Android 单元测试和 Debug 构建成功。
- [ ] 新增事件、字段和迁移保持旧客户端安全回退。
- [ ] 未将 token、密钥、运行时状态、APK 或本地配置提交到 Git。
- [ ] 工作树干净；若推送，先完成上述验证并只推送功能分支。
