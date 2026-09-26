# CameraX 与 ARCore 共享相机可行性评估

日期：2026-09-27
代码基线：`feature/integrated-enhancement` / `974a007`
状态：只读技术评估；不是已批准的实现规格。

## 结论

不建议让当前 CameraX 管线与 ARCore `SharedCamera` 直接共用同一个相机设备。Google 的 `SharedCamera` 契约建立在 ARCore 与应用自建的 Camera2 `CameraDevice` / `CameraCaptureSession` 上；官方示例由 ARCore 包装 Camera2 打开和 session 回调，并自行管理 GL 预览 surface 与可选 `ImageReader`。它不是可直接插入 `ProcessCameraProvider` 的 CameraX use case。

AndroidX Camera2 Interop 允许在 CameraX 管线中设置部分 Camera2 参数并观察回调。当前 AndroidX `SessionConfig` Camera2 interop 文档（该接口标注从 CameraX 1.7.0-alpha03 起提供）警告：从回调获得的原始 Camera2 对象若被直接用于改变 session/device 状态，会绕过 CameraX 管线管理，可能造成状态失步、流中断或崩溃；本项目固定在 CameraX 1.3.4。官方资料没有给出把 ARCore `SharedCamera` 的回调包装/相机所有权交给 CameraX 的受支持集成方式。因此这不是对理论上绝无可能的断言；它是针对本项目的工程判断：应把“CameraX + ARCore 同时管理同一 camera session”视为不受支持路径，除非后续独立原型和设备测试证明存在稳定、官方兼容的 seam。

## 当前项目状况

- `android/app/build.gradle` 使用 CameraX `1.3.4`，包含 `camera-core`、`camera-camera2`、`camera-lifecycle` 和 `camera-view`，没有 ARCore 依赖。
- `MainActivity.startCamera()` 通过 `ProcessCameraProvider.getInstance()` 创建 `Preview` 和 720x540 `ImageAnalysis`，再用 `bindToLifecycle()` 绑定 CameraX。
- `ImageAnalysis` 同时承载本地现实线索分析，以及用户显式开启、节点在线时的可选图像上传；远程上传默认关闭。
- 进入现实镜头目前仍启动/沿用 CameraX。`RealityLensView` 的 ARCore pose source 为空，因此当前姿态始终回落 Canvas；`RealityCaptureController` 是状态协调器，不是 CameraX/ARCore session 适配器。

## 可选架构

### A. 现实镜头改由普通 ARCore Session 独占相机（推荐）

进入现实镜头前记录 CameraX 是否运行，解绑 CameraX use cases，再创建普通 ARCore `Session` 并渲染相机背景；现有 Canvas Mote/线索视图叠加其上。退出时关闭 ARCore Session，只在进入前 CameraX 正在运行时才重新绑定 CameraX。若设备不支持、安装被拒或 session/tracking/性能失败，就恢复纯 Canvas。

优点：生命周期和相机所有权简单、避免两个框架竞争 camera session；与“单相机所有者、失败回退 Canvas”的产品约束相符。代价：现实镜头需要一个 GL 相机背景 renderer；CameraX 的 ImageAnalysis 要改由 ARCore 帧图像路径提供或在不可用时退化为手动线索。ARCore `Frame.acquireCameraImage()` 可提供与当前帧对应的 YUV 图像，但文档列出暂不可用、资源耗尽等失败情况，因此分析应低频、及时释放图像并可降级。显式远程上传开关必须继续单独控制上传，不能因为接入 ARCore 而放宽默认隐私。

### B. ARCore SharedCamera + 自建 Camera2 管线

现实镜头关闭 CameraX，由 `Session.Feature.SHARED_CAMERA`、ARCore 包装的 Camera2 callbacks、自建 GL preview 和可选 `ImageReader` 共同持有 session；Companion 非 AR 状态仍用 CameraX。它保留 ARCore 与应用 Camera2 surfaces 的共享能力，但并非 CameraX 与 ARCore 直接共管。实现和生命周期比 A 复杂，还需逐设备验证输出 surface 组合；ARCore 文档说明共享模式下不能使用深度传感器，额外 surface 也会增加性能压力。

仅当后续证明 A 无法保留必须的 Camera2 控制或图像分析能力时才考虑 B；目前没有足够产品收益支撑额外复杂度。

### C. CameraX 与 ARCore SharedCamera 直接混用（不推荐）

不采用未经官方支持的回调劫持或直接关闭 CameraX 底层 session 的方式。CameraX 自己负责 Camera2 生命周期与 use case surface 管理；ARCore `SharedCamera` 又要求其特定 callback 包装及完整 surface 列表。两套所有权模型冲突，容易出现相机启动竞态、surface 重建问题和难以覆盖的厂商差异。

## 建议决策

采用 A：CameraX 只负责 Companion/普通相机模式；进入 Reality 时先释放 CameraX，再由普通 ARCore Session 独占相机。保持用户已选行为：自动尝试 ARCore；首次需要安装时由 Google 系统流程请求确认；拒绝、不支持、失效或性能降级后继续 Canvas。实际平面命中、点击放置和恢复行为仍需在 ARCore 兼容设备上实测，本评估不构成实机验收。

## 一手来源

- Google ARCore： [Shared camera access with ARCore](https://developers.google.com/ar/develop/java/camera-sharing)
- Google ARCore： [SharedCamera API](https://developers.google.com/ar/reference/java/com/google/ar/core/SharedCamera)
- Google ARCore： [Session API](https://developers.google.com/ar/reference/java/com/google/ar/core/Session)
- Google ARCore： [Frame.acquireCameraImage API](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)
- Google ARCore 官方样例： [SharedCameraActivity.java](https://github.com/google-ar/arcore-android-sdk/blob/main/samples/shared_camera_java/app/src/main/java/com/google/ar/core/examples/java/sharedcamera/SharedCameraActivity.java)
- Android Developers： [CameraX architecture and Camera2 interoperability](https://developer.android.com/media/camera/camerax/architecture)
- Android Developers： [SessionConfig.Builder Camera2 interop warnings](https://developer.android.com/reference/androidx/camera/core/SessionConfig.Builder)
