plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zaka.douyin.glass"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zaka.douyin.glass"
        minSdk = 26
        targetSdk = 34
        versionCode = 1920
        versionName = "0.1.20"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("app/AndroidManifest.xml")
            java.srcDirs("src")
            res.srcDirs("app/res")
            assets.srcDirs("app/assets")
            resources.srcDirs("app/resources")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/dydock.jks")
            val signingPassword = providers.environmentVariable("DYDOCK_KEYSTORE_PASSWORD").orElse("").get()
            storePassword = signingPassword
            keyAlias = "dydock"
            keyPassword = signingPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    compileOnly(files(rootProject.file("libs/api-82.jar")))
}
