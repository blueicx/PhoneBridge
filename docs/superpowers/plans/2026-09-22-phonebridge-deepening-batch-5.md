# PhoneBridge 全面深化：批次 5 现实探索与锚定

基线：`a9c90a5`。分支：`feature/integrated-enhancement`。本批不修改 `main`，不使用子 agent。

## 已实现

- 新增 `server/reality-event-store.js`，把区域事件生成从 `RealityEngine` 下沉为可测试模块。事件使用 `Asia/Shanghai` 活动日期、粗区域、30 分钟时间桶和模板种子生成稳定 ID，并输出方向、近中远等级、过期时间和奖励预览。
- `RealityEngine.eventsFor` 改为委托事件存储；精确坐标仍在服务端入口拒绝。
- 在线 Android 线索优先提交已刷新且未过期的服务端事件 ID；无事件、无位置或离线观察继续使用兼容的手动线索 ID。
- `reality:v2` 事件在服务端处理前校验区域、活动时间、过期时间和线索类型；旧 `reality-lens` 手动/兼容路径保留，日限额和离线七天窗口仍由成长收据处理。
- Android `RealityCueAnalyzer` 只使用本地亮度/边缘信号，默认不上传原图；远程上传必须由本机显式运行时开关打开，且仍受在线、温度、电量和帧率节流约束。
- Android `RealityCaptureController` 统一 Companion/Reality 的相机所有权；退出 Reality 只释放由 Reality 自己取得的相机，避免 CameraX 重复绑定。
- `RealityLensView` 消费附近事件的方向/距离/过期投影，并把本地地点/物体/光线提示显示为轻量观察标签；无相机权限时仍保留 Canvas 手动观察。
- `ArCoreAnchorProvider` 不再把 Canvas 结果伪标为 `arcore`，改为真实 ARCore 会话的注入适配缝；当前默认无 ARCore 会话时明确回退 Canvas，避免把模拟结果当成真平面锚定。

## 验收

- Node 定向测试覆盖确定性粗区域事件、事件过期、精确区域拒绝和生成事件的服务端验证。
- Android 定向测试覆盖本地线索确定性、默认隐私、相机所有权、区域事件近中远选择和 ARCore 注入适配/Canvas 回退。
- 完整 Node、Android 单测、Debug 构建、性能预算、敏感扫描和差异检查在提交前重新运行。

## 未宣称完成

- 当前实现没有引入 ARCore 依赖或接管相机，因此不宣称真实平面检测、点击放置、锚点追踪或 ARCore 实机验收完成；下一步可在明确采用 ARCore 会话/渲染管线后单独落地。
- 真实 GPS fix、相机权限实测、三类线索实机奖励、长时间温度/帧率/电量和网络 ADB 均留待设备可用后的独立验收。
