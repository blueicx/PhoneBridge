# Changelog

## Unreleased · feature/integrated-enhancement

- 增加 Mote 三类现实线索的每日成长、经验、等级和限时探索增益。
- 统一 Android RealityLens 的粗区域事件过滤、离线 outbox、ACK 恢复和重复事件幂等。
- 统一 Web、Android 和服务端摘要中的探索成长状态；新增 `GET /api/motes/growth`。
- 增加 Debug/Release 产物清单校验、敏感路径拒绝、SHA-256 和回滚门禁。

当前版本仍是 `2.0.0` Debug 侧载基线；正式签名、ARCore 真平面和长期实机运行需单独验收。
