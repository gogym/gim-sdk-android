# 配置参考：ImConfig

`ImConfig` 是连接 IM 服务器所需的全部参数，作为 `GimImService.connect(config)` 的入参。

## 字段一览

```kotlin
data class ImConfig(
    val host: String,                          // IM 服务器地址
    val port: Int,                             // IM 服务器端口
    val userId: String,                        // 当前用户 ID
    val token: String,                         // 认证 Token
    val device: String,                        // 设备类型（mobile/desktop/web/pad）
    val deviceId: String,                      // 设备唯一标识（客户端持久化 UUID）
    val heartbeatIntervalMs: Long = 15_000L,   // 心跳间隔
    val heartbeatTimeoutMs: Long = 20_000L,    // 心跳响应超时
    val reconnectBaseDelayMs: Long = 2_000L,   // 重连基础间隔
    val reconnectMaxDelayMs: Long = 60_000L,   // 最大重连间隔
    val maxReconnectAttempts: Int? = null,     // 最大重连次数（null=无限）
)
```

## 必填字段

| 字段 | 说明 | 取值建议 |
|------|------|---------|
| `host` | IM 服务器地址 | 与服务端 `gim.netty.host` 对应 |
| `port` | IM 服务器端口 | 默认对应服务端 `gim.netty.port`（示例 3333） |
| `userId` | 当前登录用户 ID | 与服务端账号体系一致 |
| `token` | 认证令牌 | 服务端 `ImTokenVerifier` 校验；首包 `BindRequest` 携带 |
| `device` | 设备类型字符串 | 用 `DeviceType.MOBILE.value` 等；影响同类型互踢策略 |
| `deviceId` | 设备唯一标识 | **客户端持久化 UUID**，用于区分"同设备重连"与"异设备顶号"，务必稳定 |

## 心跳/重连调优

| 字段 | 默认 | 调整建议 |
|------|------|---------|
| `heartbeatIntervalMs` | 15000 | 需 **≤ 服务端读空闲超时的一半**；弱网可适当增大，但要小于服务端 idle 超时 |
| `heartbeatTimeoutMs` | 20000 | 应大于 `heartbeatIntervalMs`，为一次往返 + 抖动留余量 |
| `reconnectBaseDelayMs` | 2000 | 退避起点，第 n 次延迟 = base × 2ⁿ |
| `reconnectMaxDelayMs` | 60000 | 退避封顶，避免间隔无限增长 |
| `maxReconnectAttempts` | null | 生产一般保持 `null` 无限重连；调试可设有限次数 |

> 后台时重连间隔会强制切换为 5 分钟固定值（省电），不受 `reconnectBaseDelayMs` 影响。

## device 与 DeviceType

```kotlin
enum class DeviceType(val value: String) {
    MOBILE("mobile")  DESKTOP("desktop")  WEB("web")  PAD("pad")
}
```

`ImConfig.device` 传字符串，推荐用 `DeviceType.MOBILE.value`。服务端据此做"同类型互踢、跨类型共存"：例如两台 mobile 登录会互踢，而 mobile 与 desktop 可共存。

## 完整示例

```kotlin
val config = ImConfig(
    host = "im.example.com",
    port = 3333,
    userId = currentUserId,
    token = currentJwtToken,
    device = DeviceType.MOBILE.value,
    deviceId = Settings.Secure.getString(contentResolver, "android_id") // 或自管持久化 UUID
        ?: UUID.randomUUID().toString().also { persist(it) },
    heartbeatIntervalMs = 15_000L,
    heartbeatTimeoutMs = 20_000L,
    reconnectBaseDelayMs = 2_000L,
    reconnectMaxDelayMs = 60_000L,
    maxReconnectAttempts = null,
)

lifecycleScope.launch { service.connect(config) }
```

## deviceId 持久化建议

`deviceId` 必须**跨启动稳定**：推荐首次生成 UUID 后写入 `SharedPreferences` / DataStore，之后读取复用。若每次冷启动都变，服务端会误判为新设备顶号，触发老连接被踢。

## 相关文档

- [消息能力：心跳与自动重连](消息能力：心跳与自动重连)
- [核心概念：连接与认证](核心概念：连接与认证)
