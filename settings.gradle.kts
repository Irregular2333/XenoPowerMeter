pluginManagement {
    repositories {
        // 阿里云镜像（国内直连快），失败自动落到官方源
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") }
        }
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
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
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") }
        }
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "XenoPowerMeter"
include(":app")
include(":backdrop")

// miuix 使用 Maven Central 0.9.5-beta01（含 glass/blur 模块），无需源码集成。
// 若需跟进上游 main 分支，可将依赖改为 includeBuild（AGP 9.5.1 + Gradle 9.9+）。
