# Android 集成与生命周期

本文说明如何在 Android App 中组织 `GimImService` 的持有、生命周期联动、进程保活与依赖/混淆配置。

## 持有方式：单例服务

`GimImService` 是纯内存对象（无 Android 依赖），推荐用应用级单例持有，跨 Activity/Fragment 共享同一连接：

```kotlin
object ImHolder {
    val service = GimImService()

    fun init(context: Context) {
        // 可选：初始化 WebRTC（音视频通话需要，全局一次）
        RtcEngine.initialize(context.applicationContext)
    }
}

// Application
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ImHolder.init(this)
    }
}
```

监听 `connectionState`：

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        ImHolder.service.connectionState.collect { state ->
            when (state) {
                ImConnectionState.AUTHENTICATED -> hideConnecting()
                ImConnectionState.RECONNECTING -> showReconnecting()
                ImConnectionState.DISCONNECTED -> showDisconnected()
                else -> {}
            }
        }
    }
}
```

## 生命周期联动

在 `ProcessLifecycleOwner`（应用级前后台）中驱动，比单个 Activity 更准确：

```kotlin
ProcessLifecycleOwner.get().lifecycle.addObserver(
    object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            ImHolder.service.pauseForBackground()   // 后台：重连切低频
        }
        override fun onStart(owner: LifecycleOwner) {
            appScope.launch { ImHolder.service.reconnectIfNeeded() }  // 前台：探活重连
        }
    }
)
```

登录成功后连接，登出时断开：

```kotlin
// 登录
scope.launch { ImHolder.service.connect(imConfig) }

// 登出
scope.launch { ImHolder.service.disconnect() }
// 若整个会话结束且不再需要：ImHolder.service.dispose()
```

## 需要常驻收发：前台 Service

App 进程被系统回收后 TCP 连接会中断。若需后台持续接收消息，业界做法是配合**推送**（离线消息由服务端留存 + 推送触达，回前台再 `reconnectIfNeeded` 补收），或用前台保活 Service（受 Android 版本限制，谨慎使用）。SDK 本身不提供保活，专注通信层。

## 协程与线程

- `connect` / `disconnect` / `reconnectIfNeeded` 是 `suspend`，在 `lifecycleScope` / `appScope` 中调用；内部对阻塞 Socket I/O 已切 `Dispatchers.IO`。
- `send` 线程安全、非阻塞，可在任意线程调用。
- `ImEventListener` 回调运行在读线程，更新 UI 需切主线程；优先用 `StateFlow` + `repeatOnLifecycle` 观测状态。

## 权限清单

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 音视频通话 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<!-- 若用 LiveKit 保活/蓝牙音频（SFU） -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

危险权限（CAMERA/RECORD_AUDIO）需运行时申请。`minSdk = 26`。

## 网络安全性（明文/自签）

调试常连本地明文 TCP，如目标为 cleartext 需在 `network_security_config.xml` 允许，或仅在 debug 构建放开。生产建议对 IM 通道做 TLS 或可信网络环境。

## 仓库与依赖

本工程依赖解析仓库（`settings.gradle.kts`）：

```kotlin
repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }   // LiveKit 依赖的 WebRTC 预编译包
}
```

SDK 以 `api` 暴露：`stream-webrtc-android`、`kotlinx-coroutines`、`protobuf-javalite`、`livekit-android`。App 侧引入 SDK 即可传递获得，无需重复声明；如与现有依赖冲突，按需在 App 模块 `exclude`。

## 混淆（ProGuard / R8）

SDK 提供 `consumer-rules.pro`，会随 AAR 自动合并。Protobuf Lite 生成类与 WebRTC/LiveKit 需保留：

```pro
# 参考 consumer-rules.pro；如开启 minify，保留 protobuf 与 webrtc
-keep class io.getbit.gim.sdk.protocol.ImProto$* { *; }
-keep class org.webrtc.** { *; }
-keep class io.livekit.android.** { *; }
```

本工程 `release` 默认 `isMinifyEnabled = false`（library 不做内联混淆，交由 App 决定）。

## 单测

群通话 DTO 与协议编解码为纯 JVM 单测（`org.json` 用真实实现覆盖 Android stub）：

```bash
./gradlew test
```

## 相关文档

- [快速开始](快速开始)
- [消息能力：心跳与自动重连](消息能力：心跳与自动重连)
- [WebRTC：1:1 通话](WebRTC：1对1通话)
