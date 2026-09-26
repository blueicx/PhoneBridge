# PhoneBridge 全面深化：批次 6 可恢复交付与发布门禁

基线：`a7eb6c9`。分支：`feature/integrated-enhancement`。本批不修改 `main`，不使用子 agent。

## 已实现

- Android 版本提升为 `versionName 2.1.0`、`versionCode 3`。Release signing 支持通过 `PHONEBRIDGE_RELEASE_STORE_FILE`、`PHONEBRIDGE_RELEASE_STORE_PASSWORD`、`PHONEBRIDGE_RELEASE_KEY_ALIAS`、`PHONEBRIDGE_RELEASE_KEY_PASSWORD` 成组注入；缺少任一项不会静默生成“正式发布”假象。
- `scripts/verify_release_gates.ps1` 不再只读 Gradle 文本：使用 Android `aapt` 读取 APK 内部包名/版本，使用 `apksigner verify --verbose --print-certs` 验证实际签名和证书指纹，并把 APK 字节数、SHA-256、包名、版本、签名方案、证书 DN/指纹写入 schema v2 manifest。可选 `-ManifestPath` 输出产物清单；`release` 还必须传 `-Signed` 并提供 `PHONEBRIDGE_RELEASE_CERT_SHA256`（或参数等价物），且拒绝 Android Debug 证书。
- `server/release-gates.js` 校验 APK 内部包名、版本号和签名验证状态；`release` 频道必须签名，`internal-debug` 也必须是可验证的 Debug 产物。
- GitHub Actions 在 Debug 构建后执行实际 APK 门禁，并把 APK 与临时目录中的 manifest 作为 Actions artifact 上传；APK 不进入源码历史。
- 配对接口新增版本化 `qrPayload`，包含一次性 pairing ID、六位码、nonce、WSS/WS endpoint、指纹和过期时间；服务端仍只保存 code/nonce 哈希，claim 后立即轮换令牌。Android 增加二维码载荷解析、claim 字段构造和 `sha256:<hex>` 到 OkHttp pin 的转换；BridgeLink 在显式提供指纹时启用证书 pinning，重连继续复用目标地址、令牌和指纹。
- 诊断导出显式声明不含 secrets、原图、精确位置和连续轨迹；备份/恢复白名单扩展到 `ai-memory`、`mote-growth`、`mote-story`、`reality-state`、`proactive-policy` 等新增状态，并为备份写入文件 SHA-256 manifest。

## 验收

- Node 覆盖 release manifest、实际 metadata 约束、QR payload、配对哈希状态和诊断隐私字段。
- Android 覆盖二维码解析、claim 字段、WSS/指纹条件和 pin 格式；`aapt`/`apksigner` 对实际 Debug APK 运行。
- 正式签名 release 只在用户提供外部 keystore 后验证；没有 keystore 时只验收 `internal-debug`，不标记正式发布完成。

## 未宣称完成

- 当前没有外部正式 keystore，因此未生成或宣称正式签名 APK，也未上传商店。
- 目标手机 `192.168.101.68:41253`、真实局域网 WSS 配对、二维码相机扫描、ARCore、GPS fix 和长时间运行仍需设备可用后的独立验收。

CI 复核：提交 2ffadf7 的 GitHub Actions 因递归测试发现运行到 node_modules/dijkstrajs/test/dijkstra.test.js，该依赖测试要求其未安装的 expect.js 而失败；应用自身 134 项全部通过。工作流现限定为 server/*.test.js，本地复验 134/134；此修复推送后的最新 Actions 仍需确认。

## 后续续作记录（2026-09-26）

以 `1d0f52b` 为续作基线：完成 Android CameraX/ZXing 本机扫码、v2 二维码 claim、HTTPS/TLS DER 指纹校验和失败保留旧配置；服务端配对前置条件与一次性 claim 已接通。运行时备份/恢复加入完整清单验证、JSON 状态净化和迁移、空备份保护、只读验证及失败回滚；新增认证 flush API，并确保 WorkspaceStore debounce 写入先落盘。聊天历史作为可恢复用户状态保留；令牌、日志、原始媒体和精确坐标继续排除。

验收记录：Node 全量 134/134；PowerShell 临时目录备份/恢复故障注入通过；Android 单测与 Debug 构建通过。`lintDebug` 因分析器依赖缺失且仓库下载连接挂起而未完成，离线复验显示 `intellij-core-31.5.2.jar`、`kotlin-compiler-31.5.2.jar` 未缓存。当前 ADB 无已连接设备，因此真实扫码/claim 未验收。正式签名、角色专属剧情和真实 ARCore 当时留待后续增量，进度见下方续作记录。

## 续作 2：专属剧情与跨平台签名解析（2026-09-26）

- 故事目录 schema v2 保留 12 条通用剧情，新增 20 条逐形态专属剧情；通过当前 `activeId` 限定触发，迁移保留旧完成/领奖状态。
- Android 图鉴现在展示专属剧情与完成条件，对已完成项提供领奖入口并在成功后刷新服务端投影。
- Actions run #26 的 Node、Android 单测/Debug 构建等通过，但 APK release manifest gate 报告读不到 signer SHA-256。旧 run 的日志包返回 403，无法读取 Ubuntu 的原始 signer 行；本机复现定位到 `Out-String` CRLF 行尾不匹配，解析器现接受 CRLF、ANSI、连续、冒号/空格分隔格式，并有回归测试。该修复与 CI 报错位置吻合，仍需新 CI 证实。
- Node 全量为 137/137；Android `:app:testDebugUnitTest :app:assembleDebug` 成功。版本已按计划升至 `2.2.0` / `versionCode 4`，实际 APK 包名/内部版本一致，Debug release gate 返回 `{"ok":true,"errors":[]}`；APK SHA-256 为 `92c58c82e456dad6917e9783e917dc4e34210f928ea3ddfbe8db5833bef5b9db`。
- 当前本机仅有 C/D/E/F 固定盘，没有离线恢复介质，因此尚未安全生成正式签名身份/keystore。ADB 列表为空，已知端口 `39663`、`41253`、`43003` 均不可达；扫码/重连与 ARCore 真平面/锚点仍未验收。
- `lintDebug --offline` 明确报缺 `kotlin-compiler-31.5.2.jar`；在线分析器依赖下载尝试被用户中断，Lint 未完成。修复后的 GitHub Actions 尚未由本轮提交触发。

## 续作 3：剧情隔离与领奖恢复（2026-09-27）

- 专属剧情只能由当前激活 Mote 对应的事件局部触发；关系等级剧情需当前关系事件真实跨过对应门槛；历史累计状态不能在切换角色时直接解锁专属故事。
- 故事领奖先持久化领取状态，再用 `story:<id>` 稳定关系收据应用 XP；重试会补齐“领取已保存、XP 写入中断”的状态。故事收据不受 512 条普通关系事件保留窗口影响。
- 故障注入覆盖关系持久化失败、服务重建、重放去重，以及超过 512 条普通交互后的故事领奖重试。
- 验收：Node **142/142**；Android 单测/Debug 构建通过；备份/恢复、签名解析、敏感扫描和性能预算通过。在线 Lint 因 Google Maven TLS 握手失败未完成。Debug APK 为 `2.2.0` / code `4`，SHA-256 `03de9eec453d9332f58099f069e9aa4cdefdb88517bd2ac33c680ec20cd717de`，不是正式签名版。实机配对、GPS/镜头、ARCore、正式 keystore 仍待验收。
- GitHub Actions run #29（`cf45596`）已全绿，真实 APK 门禁与 artifact 上传通过；确认 Ubuntu `V2 Signer` 多行 SHA-256 格式修复有效。
- 本机 Android Lint 因 Google Maven TLS 握手失败未运行到分析器；CI 已新增 `:app:lintDebug`，待新 run 验收。Xperia 当前在线但仍装 `2.1.0`，遥测显示充电 0%、42.2°C；保留用户数据，未在高温低电条件下升级或运行相机/长测。
