import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// リリース署名用のkeystore.propertiesはリポジトリの外(ホームディレクトリ配下)に置く。
// このファイルが無い環境(他マシン・CI等)でも`assembleDebug`は普通に動くよう、
// リリース署名は存在チェックした上で任意に適用する。
val releaseKeystorePropertiesFile = File(System.getProperty("user.home"), ".android-keystores/qmk-remap-browser/keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.exists()) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.moomatechnica.qmkremap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moomatechnica.qmkremap"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // 画面は Material 3 Expressive。Expressive の部品とテーマは material3 1.5 の alpha でだけ公開されている。
    // 1.5.0-alpha19 以降は compileSdk 37・AGP 9.1 が要るので、それより前で止める
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.moomatechnica:qmk-usb-hid-bridge:1.0.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
