# PhoneBridge 批次 A：可靠性与发布闭环

## 目标

在 `feature/integrated-enhancement` 上补齐运行时状态的可恢复性、健康检查、脱敏日志和发布前验证，同时保持已有 HTTP、WebSocket、Android 协议兼容。批次 B/C 和实体机验收不包含在本批次内。

## 实现边界

- `RuntimePersistence` 以 schema v2 保存 JSON envelope：`schemaVersion`、`savedAt`、`checksum`、`state`。
- 写入使用同目录临时文件加原子 rename；替换前保留有限数量 `.bak.N`，加载时验证 checksum。
- 旧版裸 JSON 自动迁移；无法解析或校验失败的当前文件改名为 `.corrupt-*`，再尝试最近合法备份，否则使用安全默认值。
- Workspace、timeline、Mote、关系/任务和 provider 非敏感设置使用同一持久化管理器；密钥字段在写入前再次剔除。
- `/health/live` 只表示进程存活；`/health/ready` 汇总持久化、workspace、token 检查并在未就绪时返回 503。
- 结构化日志统一输出 JSON，并对敏感字段和值进行脱敏；访问令牌轮换只报告成功，不把新令牌放进 API 响应。

## 运维脚本

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/backup_runtime.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/restore_runtime.ps1 -Source <backup-directory>
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/scan_secrets.ps1
```

备份脚本只复制状态快照及其备份，不复制 `access.token`、日志、相机帧、截图、APK、依赖目录或临时文件。恢复前应停止 PhoneBridge 节点，并在恢复后检查 `/health/ready`。

## 发布验收

```powershell
node --check server/index.js
node --test server/*.test.js
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench_workspace.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/scan_secrets.ps1
git diff --check
android\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

GitHub Actions 对 Node、协议回归、性能预算、敏感扫描、Android 单测/构建和干净工作树重复检查。手机真实 AR、PTT、线索点击和长时间运行仍是后续手工验收。

发布产物清单见 [`phonebridge-batch-a-release-manifest.json`](phonebridge-batch-a-release-manifest.json)，其中明确记录 schema 版本、分支、产物、无密钥和实机验收延后状态。

## 回滚

1. 停止节点并保留当前 runtime 目录和日志用于审计。
2. 从 `runtime-backups/runtime-<timestamp>` 选择最近一次完整备份。
3. 使用 `restore_runtime.ps1` 恢复状态文件；不要恢复 `access.token`。
4. 若代码回滚，回滚批次 A 提交本身，不执行 `reset --hard` 清除其他用户改动。
5. 启动后检查 `/health/live`、`/health/ready`、`/api/diagnostics`，再进行 Node/Android 验证。
