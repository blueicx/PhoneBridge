# PhoneBridge 全面深化批次 4：角色差异与完整成长

## 实施内容

- 新增 `server/mote-story.js`，固定 12 个剧情事件；触发、完成和领奖分离，完成记录、领奖记录和来源事件持久化，重复事件与重复领奖键幂等。
- Mote 故事接入激活、对话、成功任务、任务恢复、现实探索/三类线索、关系等级、增益和形态解锁；新增 `GET /api/motes/story` 与 `POST /api/motes/story/:id/claim`，快照、图鉴和 WebSocket `mote.story` 共用同一投影。
- 扩展服务端行为提示，保留旧 `version: 1`，额外提供动作节奏、视觉预设、角色颜色、任务偏好、情绪偏置和能力；不同角色对健康、探索和任务状态给出不同注视方式。
- 新增 Android `MoteVisualProfile`/`MoteBodyKind`，20 个形态共享一份视觉注册表；14 个探索形态在 `CompanionView` 与 `RealityLensView` 使用不同轮廓、配色、动作速度和粒子表现，未知形态回退星核。
- 新增 Android `MoteStoryProtocol` 和图鉴剧情摘要，缓存剧情投影并响应 `mote.story`；扩展统一 `WorkspaceEventTypes.MOTE_STORY`。

## 验收

- Node 定向：剧情目录、增量触发、重启恢复、领奖幂等、行为角色差异和工作区 API 通过。
- Android 定向：20 个配置、14 个独立 body kind、未知回退、行为提示和剧情协议解析通过。
- 本批仍不宣称 ARCore 真平面、正式签名、完整 GPS fix 或长时间实体机运行完成；这些属于批次 5/6 或独立实机验收。
