plugins {
    // 根项目钉住插件类路径（NIA 同款纪律）——所有子项目构建脚本共享同一插件版本，
    // 防传递依赖版本漂移；与主项目 Android-Guru-Agent 的版本矩阵完全对齐：
    // Gradle 8.14 / AGP 8.13.2 / Kotlin 2.3.21。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
