# PhoneBridge ARCore Reality Session 实施计划

目标：在 `feature/integrated-enhancement` 实现可选、可回退的真实 ARCore Reality 相机、平面命中放置和 Canvas Mote 投影；保持单一相机所有者、现有离线线索/奖励路径及默认不上传原图。

架构：`RealityCaptureController` 管相机 owner 与 entry/restore；纯 Kotlin install/thermal/projection policy 提供可测试 seam；`ArCoreRealityRenderView` 独占 ARCore Session 与 GL camera background；`RealityLensView` 只消费不可变 tracking snapshot 并把未命中的空白触摸交给放置回调；`MainActivity` 负责权限、安装确认和模式接线，不直接持有 ARCore `Frame`/`Anchor`。

技术栈：Android Kotlin/JVM tests、CameraX 1.3.4（非 AR fallback/Companion）、固定 ARCore SDK `1.54.0`、GLES 2.0、Node test runner。执行不使用子 agent。

## 任务 1：相机所有权策略与回退策略

- [x] 1.1 先扩展 `RealityDeepeningTest`，断言进入时捕获 CameraX 状态、进入 AR 前 owner 归零、AR owner 与 CameraX 互斥、退出只恢复进入前相机、热回退不恢复，以及普通 fallback 可恢复原状态；运行 `:app:testDebugUnitTest --tests '*RealityDeepeningTest*'` 并确认新断言按预期失败。
- [x] 1.2 扩展 `RealityCaptureController.kt` 为纯状态机（NONE/CAMERAX/ARCORE、Reality entry、restore intent、thermal lockout），加入 CameraX generation 校验与 camera_on/off 意图决策；只实现使测试通过的逻辑。
- [x] 1.3 新增 `RealityRuntimePolicyTest.kt`：先覆盖 40°C 即时热回退、低于 24fps 三个完整 2 秒窗口、短时帧抖动不回退、低于 38°C 连续 60 秒后仍需显式相机启动、安装请求只提示一次、恢复后用非强制安装检查；先运行看到预期失败，再新增最小纯 Kotlin policy。
- [x] 1.4 重新运行对应 Android 定向测试，检查 owner 状态转换无非法组合。

## 任务 2：坐标投影、Anchor 快照和 Reality 交互 seam

- [x] 2.1 先为 `RealityProjection` 写测试：NDC 到 View 像素映射、屏幕外/非有限投影隐藏、tracking 暂停隐藏且不回用旧坐标、命中前不创建 Anchor、关闭 session 清除放置状态；运行定向测试确认失败。
- [x] 2.2 新增不可变 `RealityTrackingSnapshot` 与纯 `RealityProjection.kt`，实现有限坐标与 viewport 校验、投影缩放、tracking/placed 状态和 anchor 清理状态。
- [x] 2.3 修改 `RealityLensView.kt`：AR tracking 时只消费真实投影；失去 tracking 隐藏 Mote 锚定 sprite 但保留线索；现有角色/线索点击优先，只有未命中既有交互且未放置时才将屏幕坐标回调给 AR session；非 AR 模式保留当前传感器 Canvas 行为。
- [x] 2.4 运行 `:app:testDebugUnitTest --tests '*RealityProjectionTest*' --tests '*RealityAnchorTest*'`。

## 任务 3：真实 ARCore Session 与 GLES 相机背景

- [x] 3.1 先给 `RealitySessionLifecycle`/渲染状态写 fake-port 测试，覆盖 resume/pause/close 顺序、启动失败清理、当前 entry 单次尝试、tracking snapshot 和 plane-hit 结果；确认红灯。
- [x] 3.2 在 `android/app/build.gradle` 固定 `com.google.ar:core:1.54.0`；Manifest 加 `com.google.ar.core=optional`，不声明 required AR hardware，保留既有 Camera permission/非 AR 兼容。
- [x] 3.3 新增 `ArCoreRealityRenderView.kt` 和纯 session lifecycle seam：ARCore 安装后创建标准（非 SharedCamera）`Session`，启用水平/垂直 plane finding；GL thread 执行 `Session.update()`、display geometry、external-OES camera background、FPS/温度 guard、当前帧平面 hit-test/Anchor 更新和 Camera pose 到 view 坐标投影；`Anchor`/`Frame` 仅在 session 内存中，销毁时释放。
- [x] 3.4 对 CPU frame 采用 240ms 本地分析节流，异常/不可用则跳帧且始终关闭 Image；远程帧仅在现有显式 opt-in、在线、热量/电量节流均允许时异步编码发送，默认路径不分配或上传原图。
- [x] 3.5 运行 ARCore adapter fake、projection、安装/质量策略定向测试和 `:app:compileDebugKotlin`。

## 任务 4：Activity 生命周期、CameraX 互斥和 fallback 集成

- [x] 4.1 在 `activity_main.xml` 的 `previewFrame` 增加默认隐藏的 AR GL surface，位于既有透明 Canvas overlay 下方；不改变沉浸启动与 Reality 线索布局。
- [x] 4.2 修改 `MainActivity.kt`：进入 Reality 时先使未完成 CameraX generation 失效并 unbind，再自动检查/请求 ARCore 安装并建 Session；安装确认只出现一次，返回后非强制复查。权限拒绝、不支持、安装拒绝、Session/camera/GL 初始化错误退化到 CameraX+Canvas（无权限则纯 Canvas）。
- [x] 4.3 接入 `onResume/onPause/onDestroy` 与 Activity 重建恢复；Reality 活跃时 `camera_on` 不得启动第二个 owner；`camera_off` 释放当前 CameraX/ARCore owner并进入手动 Canvas。前/后摄偏好不因 AR 使用后摄而变化。正常退出恢复进入前 CameraX 状态；40°C/质量回退的热保护优先并维持冷却锁定。
- [x] 4.4 本机相机温度通过 Battery changed 温度（/现有遥测可用时）更新 guard；温度阈值和 fps 窗口走纯 policy。所有状态以短中文提示显示，不泄漏异常栈或图像数据。
- [x] 4.5 运行 Reality/相机相关 Android 全部单测、`assembleDebug` 和 `lintDebug`。

## 任务 5：回归、审查与交付

- [x] 5.1 运行 Node 全量 `node --test server/*.test.js`、Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain`、性能预算、敏感扫描、APK release-gate、`git diff --check`；每个结果单独记录，Lint/CI/设备不可用不冒充通过。
- [x] 5.2 人工审阅相机 owner 唯一性、Activity 重建/安装回流、frame close/隐私门控、GL 坐标/overlay 顺序和资源释放；修复发现项并重新验证。
- [x] 5.3 更新 `README.md`、`HANDOFF.md` 和本规格：区分自动化通过、GitHub Actions、ADB 当前状态、实机 ARCore 平面/退出恢复与 30 分钟温度测试待验收；设备不可用时明确列为未验收。
- [ ] 5.4 在功能分支提交变更并推送，确认最新 GitHub Actions 状态后报告提交、测试和仍待设备验证的项目。

## 明确边界

- 不使用 ARCore `SharedCamera`、Sceneform/SceneView 或外部云识别；正常模式仍由 CameraX 管理。
- ARCore 可选；系统安装确认由 Google Play Services for AR 自带流程完成。拒绝/不支持/故障均保留 Canvas Reality。
- 不将 Debug 构建、fake session 或 Canvas fallback 描述成设备实测的真平面 AR；不生成/提交 APK、密钥、相机帧或运行时数据。
