import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.webdavcast.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.webdavcast.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"
    }

    val keystoreProperties = Properties().also {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) it.load(f.inputStream())
    }
    val hasReleaseKey = keystoreProperties.getProperty("storeFile")?.isNotBlank() == true

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // 视频播放 Media3/ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    // OkHttp 数据源: 自动处理 URL 编码/302重定向/Basic认证(ExoPlayer 默认 DataSource 处理不了中文路径+重定向)
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")

    // WebDAV + DLNA HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 地址与投屏状态持久化
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
