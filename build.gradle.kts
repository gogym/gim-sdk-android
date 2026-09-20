// GIM SDK —— 扁平单 module Android Library 工程
// 根工程本身即 library module，无子模块
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "io.getbit.gim.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 发布 release 变体，附带 sources jar
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // WebRTC：RtcEngine 公开 API 暴露 VideoTrack/EglBase 等类型 → api
    api(libs.webrtc.android)

    // Coroutines：connectionState(StateFlow)、suspend fun 出现在公开 API → api
    api(libs.coroutines.core)
    api(libs.coroutines.android)

    // Protobuf lite：ImProto lite 生成代码（GeneratedMessageLite）出现在公开 API → api
    // 与 LiveKit 传递依赖同为 javalite，全链路单一 protobuf runtime，无需排除
    api(libs.protobuf.javalite)

    // LiveKit（SFU 群通话传输层；GroupRtcEngine 公开 API 暴露 Track 类型 → api）
    api(libs.livekit.android)
    // 单测（JVM）：junit + 真实 org.json（Android stub 在本地单测中抛异常）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// ====================== Maven 发布 ======================
// 坐标：io.getbit.gim:gim-sdk-android:1.0.0
// 发布到本机 mavenLocal：./gradlew publishToMavenLocal
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.getbit.gim"
                artifactId = "gim-sdk-android"
                version = "1.0.0"
            }
        }
    }
}
