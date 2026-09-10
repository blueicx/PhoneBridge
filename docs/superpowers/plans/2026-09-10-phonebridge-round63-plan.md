# PhoneBridge Round 63 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（- [ ]）语法来跟踪进度。

**目标：** 修复干净 CI 的依赖安装路径，同时降低 Web 工作台状态传输/重绘，并补齐任务审计、Android 游标恢复和关系驱动 Mote 行为。

**架构：** 复用 WorkspaceStore 的 revision、现有 full/summary 快照和任务 API；Web 通过 URL 级 ETag 与 revision 门控避免无效解析/重绘；Android 通过持久化游标初始化 WorkspaceEventGate，继续使用现有 Room/outbox 和 Canvas 行为提示。

**技术栈：** Node.js 内置测试、ws、GitHub Actions、Kotlin/Android Gradle、Room、现有 Canvas 渲染。

---

### 任务 1：让 GitHub Actions 在干净 runner 上安装服务端依赖

**文件：**
- 修改：.github/workflows/ci.yml
- 参考：server/package.json、server/package-lock.json
- 测试：CI 中现有 Node/性能/Android 步骤

- [ ] **步骤 1：添加依赖安装和 npm 缓存**

在 actions/setup-node 后配置 cache: npm 与 cache-dependency-path: server/package-lock.json，并在 Node 测试前加入 name 为 Install server dependencies、run 为 npm ci --prefix server 的步骤。

- [ ] **步骤 2：保持测试入口兼容并做本地等价验证**

运行：npm ci --prefix server --ignore-scripts；node --test server/*.test.js。

预期：依赖安装成功，Node 测试不再出现 Cannot find module 'ws'。

- [ ] **步骤 3：检查工作树**

运行：git diff --check；git status --short。

预期：没有 node_modules、运行时 state 或日志进入 Git 变更；实体机不执行。

### 任务 2：实现 ETag 感知的 Web 状态轮询

**文件：**
- 修改：server/index.js
- 修改：server/workspace-api.test.js
- 参考：server/workspace-performance.js

- [ ] **步骤 1：补充失败的 HTTP 断言**

在现有 workspace API 集成测试中，先读取 /api/state 的 ETag，再用 If-None-Match 重复请求，断言返回 304 且 body 为空；执行一次任务动作后再次请求，断言 ETag 变化。

- [ ] **步骤 2：运行目标测试确认缺口**

运行：node --test server/workspace-api.test.js。

预期：新增断言能约束 304 和 revision 变化行为，不删除既有认证、WebSocket 和任务断言。

- [ ] **步骤 3：改造内联 Web API helper**

为 api() 增加按请求路径保存 ETag 的 Map。GET 请求带上已有 ETag；收到 304 时返回明确的未变化结果，调用方跳过 JSON 解析和 DOM 更新。非 GET、401 和其他错误继续使用现有错误处理。

- [ ] **步骤 4：分离轮询频率并保留 revision 门控**

完整状态使用较低频率并利用 ETag，summary 工作台继续使用较高频率和 requestAnimationFrame 合并；响应为 304 或 revision 未变化时不重建任务、日志和 Mote 列表。

- [ ] **步骤 5：运行目标验证**

运行：node --check server/index.js；node --test server/workspace-api.test.js server/workspace-performance.test.js。

预期：所有目标测试通过，304 body 为空，任务动作仍能刷新工作台。

### 任务 3：补齐 Web 任务工作台的筛选、详情和审计摘要

**文件：**
- 修改：server/index.js
- 修改：server/workspace-api.test.js
- 参考：server/workspace-core.js

- [ ] **步骤 1：补充任务 API 验收断言**

创建 pending、running 和 failed 任务，调用 GET /api/tasks?state=... 与 GET /api/tasks/:id/audit，断言筛选只返回目标状态，审计记录包含动作和 actor。

- [ ] **步骤 2：运行目标测试**

运行：node --test server/workspace-api.test.js server/workspace-core.test.js。

预期：新增断言明确约束筛选和审计字段。

- [ ] **步骤 3：实现工作台筛选和详情渲染**

在现有工作台增加状态统计/筛选控件；选中任务时展示 state、progress、runner、result/error 和最近审计记录。筛选和详情只消费已取得的数据，不为每个按钮请求完整状态。

- [ ] **步骤 4：完善动作反馈**

任务动作执行期间禁用对应按钮；成功后刷新 summary；失败后在选中任务详情显示服务端错误。继续使用现有 idempotency-key、esc() 和状态转换校验。

- [ ] **步骤 5：运行全量 Node 验证和静态扫描**

运行：node --test server/*.test.js；git diff --check。

预期：Node 全量通过，新增 UI 字符串没有明文凭据、任意 shell 或未转义用户字段。

### 任务 4：修复 Android 游标恢复并接入关系提醒强度

**文件：**
- 修改：android/app/src/main/java/com/phonebridge/WorkspaceProtocol.kt
- 修改：android/app/src/main/java/com/phonebridge/MainActivity.kt
- 修改：android/app/src/main/java/com/phonebridge/MoteBehaviorEngine.kt
- 视需要修改：android/app/src/main/java/com/phonebridge/CompanionView.kt、android/app/src/main/java/com/phonebridge/RealityLensView.kt
- 测试：android/app/src/test/java/com/phonebridge/WorkspaceProtocolTest.kt
- 测试：android/app/src/test/java/com/phonebridge/ExpansionProtocolTest.kt

- [ ] **步骤 1：添加游标恢复和 wire 字段测试**

覆盖以下行为：从 revision 7 初始化 gate 后拒绝 revision 6；snapshot revision 9 后清除 gap；拒绝重复 eventId；MoteBehaviorOutput.fromWire 读取 reminderStrength，缺失字段回退默认值。

- [ ] **步骤 2：运行目标 Android 测试确认缺口**

运行：.\\gradlew.bat :app:testDebugUnitTest --tests com.phonebridge.WorkspaceProtocolTest --tests com.phonebridge.ExpansionProtocolTest --no-daemon --console=plain。

预期：新测试在实现前失败，不改动设备状态。

- [ ] **步骤 3：实现 gate 初始化和 snapshot 恢复**

从 workspace_meta 的已保存 revision 初始化 WorkspaceEventGate；处理 snapshot 时同步 gate 状态；gap 时只请求一次 snapshot，恢复后清除 gap 并保存 revision。

- [ ] **步骤 4：解析并消费 reminderStrength**

为 MoteBehaviorOutput 增加尾部兼容字段和 JSON 默认回退；现有 Canvas 只读取统一行为提示，通过提醒强度影响光环/主动提醒，不增加形态分支。

- [ ] **步骤 5：运行完整 Android 验证**

运行：.\\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain。

预期：测试和 Debug 构建成功；不运行 ADB 或实机验收。

### 任务 5：收口、交接和 Codex 验收

**文件：**
- 修改：README.md
- 修改：HANDOFF.md
- 检查：.gitignore、Git 索引、所有变更文件

- [ ] **步骤 1：更新文档**

记录 CI 依赖安装、ETag 304、任务工作台、Android 游标恢复、Mote 提醒强度和未执行实机验证；命令必须与实际通过的命令一致。

- [ ] **步骤 2：执行全量验收**

运行：node --check server/index.js；node --test server/*.test.js；powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench_workspace.ps1；git diff --check；.\\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain。

预期：Node、性能预算和 Android 全部通过；性能输出记录实际值；没有 ADB 进程或设备写入。

- [ ] **步骤 3：完成敏感信息和运行时文件扫描**

运行：git status --short；对 staged diff 执行凭据模式扫描；检查 git ls-files 中没有 access.token、node.lock、runtime-state.json、APK 或日志。

预期：没有新增凭据命中，没有运行时文件进入索引；若出现命中，先移除或修正。

- [ ] **步骤 4：Codex 审查并上传**

Antigravity 不执行 push。Codex 在确认 diff、测试、工作树和远端分支后，提交清晰的 Round 63 commit，推送 feature/integrated-enhancement，核对本地/远端 SHA 一致；main 不变。

