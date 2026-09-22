# PhoneBridge 批次 4：发布门禁与收口说明

## 范围

本批把“代码通过”与“可交付产物”分开记录，新增纯逻辑发布清单校验 `server/release-gates.js` 和 PowerShell 入口 `scripts/verify_release_gates.ps1`。

- `internal-debug` 允许未签名 Debug APK，仅用于当前功能分支的侧载和实机回归。
- `release` 必须显式声明外部 keystore 签名；仓库不保存 keystore、token、日志、画面、截图或 APK。
- 产物清单固定记录版本号、versionCode、分支、commit、大小和 SHA-256。
- 默认拒绝 `main/master`、敏感文件路径、无效 hash、空产物和不一致的版本信息。
- CI 在 Android Debug 构建后执行门禁，并继续执行敏感扫描、差异检查和干净工作树检查。

## 回滚

1. 先保存运行时状态：`scripts/backup_runtime.ps1`。
2. 代码回滚只回退本批提交或使用 GitHub 上一个已验证 commit；不使用 `reset --hard` 清理用户改动。
3. 需要恢复运行时状态时使用 `scripts/restore_runtime.ps1`，再重启同一 PhoneBridge 节点。
4. 正式签名发布前必须重新生成清单、验证签名和 APK hash；本批 Debug 证据不替代签名发布验收。

## 当前边界

发布门禁、Node/Android 构建和协议测试可以在 CI 完成；ARCore 真平面、真实 GPS fix、PTT、通知/小组件视觉和数小时温度/电量仍属于实机验收，不在 CI 中伪造为通过。
