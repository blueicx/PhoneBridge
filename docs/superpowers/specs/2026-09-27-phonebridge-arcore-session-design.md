# PhoneBridge ARCore Reality Session 设计规格

日期：2026-09-27
代码基线：`974a007`；文档基线：`c16dd96` / `feature/integrated-enhancement`
状态：已实现并进入验证/交付；ARCore 兼容设备上的系统安装、相机画面、平面锚定和热回退仍须实机验收。

## 目标

把 Reality 镜头从当前“CameraX 画面 + Canvas/传感器定位”升级为可选的 ARCore 世界跟踪：检测平面、允许用户把当前 Mote 放到检测到的平面上，并将真实 Anchor 的姿态投影到既有 Canvas Mote 绘制坐标。ARCore 不可用时仍保留原 Reality 功能和手动线索路径。

必须保持：单一相机所有者；ARCore 自动尝试；首次需要安装时调用 Google ARCore 系统安装流程并由用户确认；失败可回退；不保存原图、不默认上传原图；现实线索和奖励不依赖 ARCore；应用仍从沉浸伙伴界面进入，不新增繁杂设置。

## 实施前上下文

- Android 使用 CameraX `1.3.4`。`MainActivity.startCamera()` 通过 `ProcessCameraProvider` 同时绑定 `Preview` 与 `ImageAnalysis`；图像分析支持本地 Reality cue 与显式打开后的联网上传。
- 实施前的 Reality 入口会保持或启动 CameraX。`RealityLensView` 通过 Android Canvas 绘制 Mote/线索，并用传感器位置计算屏幕位置。
- `ArCoreAnchorProvider` 仍作为通用 Canvas/投影适配缝；真实 Reality Mote 坐标由 ARCore Session Anchor 每帧投影生成，不通过伪造 pose 标记为 ARCore。
- 现实镜头主要视图位于 `previewFrame` 中，`PreviewView` 和 `RealityLensView` 目前叠放其内。`RealityLensView` 已处理 Mote 触摸及线索节点触摸。
- 现有原图上传偏好 `allow_remote_camera_upload` 默认为关闭；线索奖励通过既有 `eventId` 收据，不应该被 AR session 生命周期改变。

CameraX 与 ARCore `SharedCamera` 的一手来源评估见 [`2026-09-27-camerax-arcore-sharing-assessment.md`](2026-09-27-camerax-arcore-sharing-assessment.md)。本规格选择的不是 CameraX/ARCore 共享同一底层 session，而是在 Companion 与 Reality 模式之间切换相机所有权。

## 方案与选择

1. **普通 ARCore Session 独占 Reality 相机（选定）**：Reality 进入时先解绑 CameraX，再启动 ARCore Session 与 GL 相机背景；Canvas Mote 作为透明叠层。离开 Reality 时关闭 Session，只在进入前 CameraX 正在运行时恢复它。接口与生命周期最清楚，且满足单一所有者约束。
2. **ARCore SharedCamera + 自建 Camera2**：技术上受官方支持，但要求自行管理 Camera2 camera/session callback、ARCore surfaces 和 GL 预览，整体替换 Reality 的相机管线；在没有产品必需能力的情况下复杂度偏高。
3. **CameraX 和 ARCore SharedCamera 直接共管**：没有本项目可依赖的官方所有权适配接口，不采用底层回调劫持或跨过 CameraX 管线直接操作 Camera2 session 的方案。

## 模块与职责

### RealityCameraCoordinator

唯一的相机所有权状态协调者。`RealityCaptureController` 校验 owner、entry 与恢复意图；`MainActivity` 作为 Android 平台适配层按协调结果串行调用 CameraX bind/unbind 和 ARCore view 生命周期，不能并行持有相机。

内部状态至少表达：`NONE`、`CAMERAX`、`ARCORE` 当前 owner；是否进入 Reality；进入 Reality 前 CameraX 是否在运行；当前 AR 尝试的回退原因。状态转换和 restore decision 使用纯 Kotlin policy 测试。异步 CameraX 启动须使用已有 generation/session 取消语义，防止进入 AR 后迟到的 CameraX callback 抢回相机。

主要不变量：

- 任一时刻 CameraX 和 ARCore 不能同时持有相机。
- Reality 进入时记住 CameraX 原状态；离开时仅据此决定恢复，不能把 Reality 临时开启的 CameraX 永久留开。
- ARCore 活跃时收到 `camera_on` 不得另起 CameraX；收到 `camera_off` 必须释放实际 camera owner 并切换到无相机 Canvas 手动模式。
- 进入 ARCore Reality 临时使用后摄，不改写用户的 CameraX 前/后摄偏好；退出后按原偏好恢复。

### ArCoreRealitySession

封装 ARCore SDK、安装/兼容性检查、Session 与 Anchor 生命周期、GL 渲染线程、平面 hit-test、相机帧和跟踪状态。对 UI 提供窄接口，以 Reality 意图为输入，以不可变状态快照/回调为输出；不把 ARCore SDK 类型暴露给 `MainActivity` 或 Canvas view。

逻辑接口只需表达以下操作：开始 Reality 尝试、Activity resume/pause、将屏幕点击转换为放置请求、关闭 session。状态输出至少有 `checking/installing/starting/tracking/searching/fallback/stopped`、回退原因和可选 Mote 屏幕投影。Session 创建/配置在专用串行执行器完成；Session resume/pause 遵循 Activity 与 GL surface 生命周期顺序；`Session.update()`、Frame 和 Anchor 操作限定 GL 线程；界面更新回主线程。

### RealityRenderHost 与 RealityLensView

- `RealityRenderHost` 持有 GLES/GL surface 并渲染 ARCore 相机背景、显示旋转/裁剪适配和每帧 `Session.update()`；不会把相机图像写文件。
- 现有 `RealityLensView` 继续以 Canvas 绘制 Mote、雷达/线索和短状态提示。新增输入是统一屏幕坐标系下的 `RealityTrackingSnapshot`，不允许视图直接访问 ARCore Session。
- 使用一个 viewport transform 统一处理 GL viewport、竖屏方向、相机纹理裁剪和 Canvas overlay 坐标，使 plane hit-test 与 Mote 投影命中同一画面区域。
- Mote 的身体仍由 Canvas 画成 2D sprite；ARCore Anchor 负责世界位置/方向跟踪，再投影到屏幕坐标。此版本不做真实网格模型、人体/物体遮挡、阴影接地或深度遮挡，文案不称作“完整 3D AR”。

### RealityFrameAnalyzer

在 ARCore 当前帧上按需获取 CPU YUV 图像，采样亮度/边缘并复用 `RealityCueAnalyzer` 的纯逻辑；频率不高于现有本地分析节流（约每 240ms 一次）。单帧图像及时关闭；不可用、过期或资源耗尽时跳过该次分析，不阻断 GL 跟踪和用户手动线索。

联网发送仍走现有明确 opt-in：`allow_remote_camera_upload=false` 时不编码/发送原图；即使开关开启，也继续要求节点在线并遵循现有温度、电量和节流策略。不得把 ARCore 必需的内部相机纹理误当作授权上传。

## 进入、放置和退出流程

1. 用户从 Reality 入口进入；保存已有 CameraX 状态，令 CameraX 新启动 callback 失效并 unbind 现有 use cases。
2. Camera 权限缺失时沿用当前按需授权行为。权限拒绝后显示简短状态，保留可手动点击的 Canvas 线索，不反复弹窗。
3. 查询 ARCore 可用性。只在 `SUPPORTED_INSTALLED` 或受支持的“未安装/版本过旧”状态继续；先前者直接建 Session，后者走 `requestInstall()`。
4. 首次请求安装使用用户确认流程。若 API 返回安装已请求，Reality 仍留在 Canvas/说明状态；系统流程令 Activity 暂停/恢复后，用 `userRequestedInstall=false` 复查。安装尚未完成、用户拒绝或 Activity 重建时不得形成提示循环。恢复所需的一次性 pending 标记只存本机 SharedPreferences，成功、放弃或退出后清除。
5. Session 启动成功后切换 camera owner 为 ARCore，开始 plane 检测。显示简短“移动设备寻找平面”提示，不显示设置面板。追踪 `PAUSED` 时临时隐藏真实锚定位置并保留线索操作；重获 `TRACKING` 后恢复投影。
6. 当未放置 Mote 时，用户轻触 Reality 画面：先由 `RealityLensView` 保留已有 Mote/线索命中；未命中交互区域的触摸转交 ARCore 当前帧 `hitTest`。只接受跟踪中的可用平面命中，创建一个 session 内 Anchor 并把其投影输入 Canvas。没有命中则轻提示继续移动设备。Mote 已放置后不因普通触摸重复创建 Anchor；本次不增加复杂的锚点管理 UI。
7. 点击三类线索仍走当前探索 coordinator 和收据流程。线索不要求 plane、Anchor 或网络；AR 跟踪丢失不撤销已收集或待同步线索。
8. 用户退出 Reality：先停止 GL 帧循环，pause/close Session 并释放 Anchor，再恢复相机协调者状态。若进入前 CameraX 活跃则按原镜头偏好重绑 CameraX；否则关闭 Reality 临时相机。

## 安装与错误回退

- 首次安装被系统拒绝/取消、设备不支持、ARCore APK/SDK 不兼容、Session 创建失败、camera unavailable 或 GL surface 初始化失败：清理半初始化资源并回到旧 CameraX + Canvas Reality；Camera permission 也不可用时显示纯 Canvas 手动线索。
- 短暂 `TrackingState.PAUSED`：留在 ARCore Session，提示重新观察，不立刻 tear down。Anchor 未 tracking 时不绘制在错误屏幕位置。
- ARCore 内部不可恢复错误：关闭 Session，标记本次进入已尝试，回退 CameraX + Canvas；不在同一次 Reality 进入中不断重启 ARCore。
- 设备温度到达 Reality 渲染上限（40°C），或 GL 渲染连续三个 2 秒窗口低于 24fps：关闭 ARCore 并退到 Canvas 手动模式、关闭相机以降负载；本次进入不自动重试。温度回退进入热锁定；单纯低帧率不建立热锁定，但只有用户明确打开普通相机时才可在本次 Reality 中恢复相机。普通设备不因短促帧抖动回退。
- 所有回退均显示简短、可理解的原因，如“AR 不可用，已切回普通镜头”或“设备偏热，镜头已暂停；仍可手动探索”。不堆栈显示异常或依赖 Web UI。
- 热保护优先于“退出时恢复原相机”规则：温度已触发 40°C 保护时，退出 Reality 也不自动恢复 CameraX；温度连续 60 秒低于 38°C 后，用户再次明确开启相机才解除阻止。低帧率回退在本次 Reality 中关闭相机，退出后仍可按进入前相机状态恢复；普通初始化/兼容性失败使用 CameraX + Canvas 回退。
- `Session` 在 Reality 退出、Activity 被销毁或启动失败时确定性释放；Activity pause 时停止帧循环并 pause Session，resume 时仅当仍处于 Reality 且此前 session 可恢复才恢复。

## 隐私与声明

- 清单声明 ARCore 为 optional，未认证/不支持 ARCore 的设备仍可安装并使用 Canvas Reality。
- 不持久化相机图像、视频、Camera texture、精确位置或 AR world map；Anchor 只存于当前 AR Session 内。
- 不新增云识别服务、第三方地图、自动照片上传或需要登录的后端。
- 本地 cues 只输出线索类型/提示，不将原始 frame 放入 Workspace event、日志、诊断导出或 outbox。
- 当显式远程图像上传偏好为 true 时，继续受现有授权门控、在线条件和节流规则约束；只异步编码最长边不超过 640px 的抽样帧；关闭开关时不复制/编码/发送帧。

## 验收规格

### JVM 单元测试

- Camera owner 状态机：CameraX 正在运行/未运行进入 Reality、AR 初始化失败恢复、退出只恢复原状态、迟到的 CameraX 启动不能抢占 ARCore、远程 camera on/off、原镜头偏好恢复。
- ARCore 安装决策：不支持不弹安装、受支持且未安装只请求一次、系统返回后以非用户强制方式复查、拒绝后 Canvas fallback、进程/Activity 重建无提示循环。
- 质量门控：短暂 fps spike 不切换；温度到 40°C 立即保护；低于 24fps 连续三窗口后 fallback；温度连续低于 38°C 达 60 秒仍需用户明确重新开启相机；fallback 后本次 entry 不自动恢复 AR。
- 坐标变换：屏幕旋转、裁切、视图比例和触摸点使用同一 transform；无效/屏幕外投影不绘制 Mote。
- Anchor 逻辑：只在 tracking plane hit 时放置；无命中不变更状态；session close 后不保留 Anchor；Anchor 状态不影响 eventId 与线索奖励。
- 图像隐私：默认设置不调用编码/上传适配器；frame image 所有正常与异常路径都关闭；图像不可用不影响其它更新。

### Android 构建和静态门禁

- `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 均通过；Node 全量回归、协议 fixture、敏感扫描、`git diff --check` 与最新 GitHub Actions 继续通过。
- ARCore SDK 依赖使用实施时确认的一版稳定且固定的版本，不用动态版本；Manifest 将 AR 能力声明为 optional。
- 至少用一个 ARCore stub/fake 覆盖 session 状态契约；单元测试通过 fake 接缝，不尝试在 JVM 中构造 Android `Session`/`Frame`。

### 设备验收（不能被单测替代）

- 在 ARCore 支持设备上验证首次系统安装确认、安装后恢复 Reality、平面检测、点按放置、旋转跟随、暂停/恢复、退出相机状态恢复和实际相机画面。
- 验证不支持设备、系统安装取消、权限拒绝/撤销、camera unavailable 和 GL 初始化失败都能正常返回 Canvas，且只存在一个相机 owner。
- 对照 CameraX 原版验证本地三类 cue、离线 event outbox、用户 opt-in 上传开关和明确关闭后的无上传。
- 连续 Reality 运行至少 30 分钟记录 FPS、温度、电量、内存和崩溃；两小时长测继续作为整机发布验收单独执行。
- Xperia 上是否支持 ARCore 以运行时官方 availability 和设备实测为准；每批实时 ADB 探测状态写入 `HANDOFF.md`，本规格不预设它必然支持 ARCore。

## 本次实现状态

- 已新增 `ArCoreRealityRenderView`，由它独占普通 ARCore Session，独立执行 GL 相机背景、平面命中、Session Anchor 投影和逐帧资源释放；`MainActivity` 不持有 ARCore `Session`、`Frame` 或 `Anchor` 类型。
- Reality 入场使 CameraX generation 失效并解绑；普通失败回退 CameraX + Canvas，温度/连续低帧率回退 Canvas 且关闭相机。显式切换普通镜头会取消待启动的 ARCore owner。
- 首次 Google 安装确认的返回标记在本机保存，Activity 重建时走无提示复查；用户退出会清除待恢复标记。
- 自动化测试、CI、ADB 与设备验收结果以本批 `HANDOFF.md` 交接记录为准；在兼容设备完成平面放置和 30 分钟温度测试前，不宣称 ARCore 实机验收通过。

## 不做

- 不实现 CameraX 与 ARCore `SharedCamera` 共管同一 camera session。
- 不引入 Sceneform/SceneView 或大型 3D 模型资产；GL 只负责 ARCore camera background，角色继续由现有 Canvas 画。
- 不做深度遮挡、墙面网格 UI、多 Anchor 家具编辑、云 Anchor、精确 GPS 绑定或原图持久化。
- 不改 Reality 奖励规则、服务端 API、Mote 图鉴或沉浸式启动策略。
- 不把未在兼容设备实测的功能描述成完整 3D AR 验收通过。

## 官方一手参考

- Google ARCore：[Enable AR in your Android app](https://developers.google.com/ar/develop/java/enable-arcore)
- Google ARCore：[Session API](https://developers.google.com/ar/reference/java/com/google/ar/core/Session)
- Google ARCore：[Frame API / acquireCameraImage](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)
- Google ARCore：[Shared camera access assessment](2026-09-27-camerax-arcore-sharing-assessment.md)
- Google ARCore 官方样例：[Hello AR Java](https://github.com/google-ar/arcore-android-sdk/tree/main/samples/hello_ar_java)
