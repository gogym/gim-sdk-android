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

    // Protobuf：ImProto.Packet(GeneratedMessage) 出现在公开 API → api
    api(libs.protobuf.java)
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
