import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "io.swarmcursors.overlay"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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

afterEvaluate {
    // 发布坐标（Spec §5）：io.swarmcursors:swarm-overlay —— 集成方 Maven/复合构建双通道
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.swarmcursors"
                artifactId = "swarm-overlay"
                version = "0.1.0"
            }
        }
    }
}

dependencies {
    // 核心引擎全量 API 对集成方透出（AgentAction/CursorFrame/... 都是公开类型）
    api(project(":core"))
    // 仅渲染/编排层需要 Main dispatcher
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
