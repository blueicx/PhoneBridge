# PhoneBridge 隐私中心与加密恢复批次

## 范围

- 提供 `memories`、`conversations`、`tasks`、`progress` 四类数据概览、加密导出与带明确口令的删除。
- 会话删除同时清理共享交接文本和时间线中的聊天数据；与对话关联的任务/Attention 按实体 ID 精确清理，不误删无关自动化数据。
- Android 清除 Room 镜像、待发送 outbox 和对应本地成长/聊天缓存；在线端通过 `privacy.deleted` 广播，离线端重连先读取近期已完成删除收据、清理本机缓存后再恢复 outbox。
- 删除期间写入口关闭，现有写请求排空；在途聊天取消后再清理。关联的终态任务运行记录、Room 任务/Attention/ActionRun 与已删除事件去重键一并移除。
- 节点运行时备份可选择 scrypt + AES-256-GCM 加密；提供本机安全口令提示、VerifyOnly 和临时解密恢复流程。

## 安全边界

- 加密档案仅包含用户所选类别，不写入服务端明文备份；口令经 HTTPS 请求，非回环明文 HTTP 导出会被拒绝。
- Android 用系统文档选择器保存密文，不将档案落入应用缓存；删除要输入精确确认句。
- 访问令牌、配对凭据、Provider 密钥、原始画面、精确位置和连续轨迹不进入导出。
- 运行时恢复要求节点停止；恢复脚本只在校验目标目录后执行原子替换并保留回滚点。

## 验收结果

- Node 全量：171/171；Android JVM：141 项、0 失败；Android `lintDebug` 与 `assembleDebug` 成功，Lint 报告 0 errors。
- 运行时加密备份/恢复故障演练、敏感扫描、APK Debug 签名/版本门禁、签名解析测试、性能预算、Node 语法检查及 `git diff --check` 通过。性能预算：`elapsedMs=1.118`、完整快照 `1694 bytes`、摘要 `48 bytes`、缓存命中 `10000`、突发广播 `1`。
- 最新内部 Debug APK 为 `com.phonebridge`，`2.2.0` / code `4`，大小 `93,345,623 bytes`，SHA-256：`52EDACD4638836414B9F24541317AA49336FFF08DE6120CC3216532DC0E4CC9E`。不是正式签名包，未加入 Git。
- Xperia XZ2 / Android 15 当前只读状态：USB ADB 在线、正在充电，电量 8%、电池温度 33.7°C；无线 ADB 当前离线，相机权限仍 `granted=false`。由于电量过低，本轮没有安装最新 APK 或启动设备，也没有触碰/授予相机权限。先前 `1552 ms` 是旧候选的启动记录，不作为本次构建验收。
- 提交 `94204d1` 已推送到 `feature/integrated-enhancement`；[GitHub Actions run 36599821041](https://github.com/blueicx/PhoneBridge/actions/runs/36599821041) 全部通过（5m03s），上传 artifact `phonebridge-debug-94204d1b0bd19b588ce78f8f887f6200bc8b3d03`（65,311,183 bytes）。CI 提示 Actions Node 20 与 `ubuntu-latest` 未来迁移，不影响本次通过结果。
- 隐私界面和系统文件选择器尚未实机点击。本记录不替代扫码、摄像头、ARCore、PTT 或长时运行验收。
