# PhoneBridge 批次 C：现实锚定与实体机验收记录

日期：2026-09-22
分支：`feature/integrated-enhancement`

## 目标

把现实镜头的投影选择、性能降级和位置隐私策略收拢成可测试的纯逻辑模块，并用可连接的 Xperia XZ2 做首轮真实启动与摄像头链路验证。

## 实现

- `RealityAnchor.kt`
  - `RealityAnchorProvider` 统一 Canvas/ARCore 锚点来源。
  - `CanvasSensorAnchorProvider` 使用方向、俯仰、距离等级和视口计算投影。
  - `RealityAnchorSelector` 只有在 ARCore 可用、温度不高于 40°C 且帧率不低于 24 时才选择 ARCore，否则回退 Canvas。
- `RealityLocationPolicy.kt`
  - 权限、定位开关、样本新鲜度、模拟位置和精度逐项校验。
  - 合格样本只转换为粗粒度区域；其他情况进入镜头离线模式。
- `RealityLensView`
  - 线索和 Mote 投影统一消费 `AnchorPose`。
  - 主界面将实时 FPS 和设备温度传入锚点选择器。
- `server/companion-summary.js`
  - 统一摘要识别 `connected`、`online` 和 `active` 健康状态词，修复双端状态不一致。

## 实机记录

设备：`Xperia XZ2 / Android 15 / arm64-v8a`
调试：`192.168.101.68:43003`，ADB server `5038`，反向代理 `9503`

已观察到：

1. Debug APK 安装成功，`versionCode=2`、`versionName=2.0.0`。
2. App 启动到 `MainActivity`，进程保持存活，未发现 App `FATAL EXCEPTION`。
3. 摄像头启动后显示在线和约 10fps，服务端 `/frame` 返回 `200`，收到约 46KB JPEG；服务端遥测读到电量 65%、温度 28°C。
4. 主动停止摄像头后 UI 返回 `CAM OFF`、`0fps`，进程仍存活。
5. 现实镜头能显示真实相机预览、Canvas Mote/线索叠加和传感器状态，点击退出后回到普通工作台。

最终 Debug APK：`android/app/build/outputs/apk/debug/app-debug.apk`，大小 `91,221,325` bytes，SHA-256 `7A32DC1F0A73CDD4A2C5F5C0191FB09D95E5F6132E92A373648E67CA11093C80`。

## 未覆盖边界

本记录不等价于 ARCore 真平面、GPS 真实授权、三类线索奖励、文本聊天、PTT、断线恢复、通知/小组件或长时间运行验收。上述项目需要单独的可重复实机步骤和结果记录。
