pluginManagement {
    repositories {
        // NIA（android/nowinandroid）同款纪律：google() 只解析 Google 系 group，
        // 防止其它坐标误走 google() 拉到非预期构件（对齐主项目 Android-Guru-Agent）。
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

// NIA 同款前置检查：JDK 不满足直接给人话报错，而不是等 AGP 在配置中期抛晦涩异常。
// 本库 JDK 17 起步，与 CI setup-java 对齐（主项目同为 17+）。
check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
    "Android-Swarm-Cursors requires JDK 17+ (current: ${JavaVersion.current()}). " +
        "Set JAVA_HOME to a JDK 17+ installation. CI reference: .github/workflows/ci.yml (setup-java temurin 17)."
}

rootProject.name = "android-swarm-cursors"

// ── 模块布局 ────────────────────────────────────────────────────────────────
// :core   —— 纯 Kotlin JVM 运动引擎（样条插值 / Fitts 时序 / 弹簧落停 / 并发控制）
// :overlay —— Android 渲染层（无障碍服务 / TYPE_ACCESSIBILITY_OVERLAY / 主题绘制）
// :sample —— 演示 App（多光标真实操作演示；后续步骤落地时加入）
include(":core")
