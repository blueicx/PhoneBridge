# PhoneBridge 批次 B：TLS 配对与侧载发布底座

## 已实现

- 当 `PHONEBRIDGE_TLS_KEY` 与 `PHONEBRIDGE_TLS_CERT` 同时存在时，Node 使用 HTTPS/WSS；缺少任一文件会直接拒绝启动配置。
- 配对指纹由证书原始字节计算为 `sha256:<hex>`，不会接受客户端提供的伪造指纹。
- 非回环绑定节点只允许 TLS；远程 `/api/pairing/claim` 必须来自加密连接。
- `/api/pairing/start` 返回 `scheme`、`url` 和证书指纹；可通过 `PHONEBRIDGE_PAIRING_HOST` 指定手机可访问的局域网地址。
- Android `PairingOffer` 拒绝没有 `wss` 或证书指纹的远程配对。
- Android 版本提升为 `versionCode 2`、`versionName 2.0.0`；没有把签名密钥写入仓库。

## 启动示例

```powershell
$env:PHONEBRIDGE_BIND = '0.0.0.0'
$env:PHONEBRIDGE_PAIRING_HOST = '192.168.1.9'
$env:PHONEBRIDGE_TLS_KEY = 'F:\PhoneBridge-secrets\phonebridge.key'
$env:PHONEBRIDGE_TLS_CERT = 'F:\PhoneBridge-secrets\phonebridge.crt'
node server/index.js
```

正式使用前应通过受信任的内部 CA 或设备侧证书指纹确认；不要把私钥、访问令牌或包含私钥的日志提交到 Git。

## 产物与哈希

```powershell
android\gradlew.bat :app:assembleDebug --no-daemon --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\print_release_hash.ps1 `
  -ApkPath android\app\build\outputs\apk\debug\app-debug.apk
```

当前只生成 Debug/未正式签名 Release 产物。正式签名需要用户自己的 keystore、别名和密码，通过 Gradle 外部环境或本机安全存储注入；这些值不能进入仓库、CI 日志或交接文档。

## 回滚

1. 停止节点，保留当前 runtime 目录和诊断日志。
2. 代码回滚到批次 B 提交前的功能分支提交，不修改 `main`。
3. 如果切换回非 TLS 节点，只绑定回环地址；不要在无 TLS 时把服务暴露到局域网。
4. 按 `phonebridge-batch-a.md` 的备份/恢复步骤恢复 schema v3 状态，再检查 `/health/ready`。

## 未完成边界

本批不宣称正式签名 APK、应用商店发布、ARCore/GPS/PTT、通知/小组件实机验收或长时间运行验证。TLS 配置仍需要真实局域网和手机连接测试。
