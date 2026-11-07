import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.swarmcursors.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.swarmcursors.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // 库本体（Manifest 合并自动带入无障碍服务声明 —— 集成方零手写验证）
    implementation(project(":overlay"))
    implementation(libs.appcompat)
    implementation(libs.material)
}
