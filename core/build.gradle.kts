import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 核心引擎唯一依赖：kotlinx-coroutines（Flow/suspend 公开 API 所需）。
    // 除此外零依赖、零 android.* import —— 可在任意 JVM 上直接单元测试。
    api(libs.coroutines.core)

    // JVM 单测（对齐主项目纪律：JUnit4 + coroutines-test，不引 MockK/Turbine）
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
