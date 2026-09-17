# SDK 使用方混淆规则（会随 aar 一起传递给 app）
# WebRTC 由 JNI 反射调用，需保留
-keep class org.webrtc.** { *; }

# Protobuf 生成代码
-keep class io.getbit.gim.sdk.protocol.ImProto { *; }
-keep class io.getbit.gim.sdk.protocol.ImProto$* { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessage { *; }

# SDK 对外公开 API
-keep class io.getbit.gim.sdk.service.GimImService { *; }
-keep class io.getbit.gim.sdk.spi.ImEventListener { *; }
-keep class io.getbit.gim.sdk.model.ImConfig { *; }
-keep class io.getbit.gim.sdk.rtc.RtcEngine { *; }
-keep interface io.getbit.gim.sdk.rtc.RtcEngineCallback { *; }
