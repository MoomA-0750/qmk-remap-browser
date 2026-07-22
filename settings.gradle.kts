pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QMKRemapBrowser"
include(":app")

// USB Host API経由のRaw HID通信ロジックはqmk-usb-hid-bridge（qmk-remap-native側とも
// 共有する独立リポジトリ）に切り出し、Gradle複合ビルドで参照する。
includeBuild("../qmk-usb-hid-bridge")
