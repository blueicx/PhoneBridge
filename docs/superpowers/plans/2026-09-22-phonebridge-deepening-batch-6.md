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

## 后续续作记录（2026-09-26）

以 `1d0f52b` 为续作基线：完成 Android CameraX/ZXing 本机扫码、v2 二维码 claim、HTTPS/TLS DER 指纹校验和失败保留旧配置；服务端配对前置条件与一次性 claim 已接通。运行时备份/恢复加入完整清单验证、JSON 状态净化和迁移、空备份保护、只读验证及失败回滚；新增认证 flush API，并确保 WorkspaceStore debounce 写入先落盘。聊天历史作为可恢复用户状态保留；令牌、日志、原始媒体和精确坐标继续排除。

验收记录：Node 全量 134/134；PowerShell 临时目录备份/恢复故障注入通过；Android 单测与 Debug 构建通过。`lintDebug` 因分析器依赖缺失且仓库下载连接挂起而未完成，离线复验显示 `intellij-core-31.5.2.jar`、`kotlin-compiler-31.5.2.jar` 未缓存。当前 ADB 无已连接设备，因此真实扫码/claim 未验收。正式签名、角色专属剧情扩展和真实 ARCore 留在后续增量。
