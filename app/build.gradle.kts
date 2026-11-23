plugins {
    id("com.android.application")
}

android {
    namespace = "com.zaka.douyin.glass"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.zaka.douyin.glass"
        minSdk = 26
        targetSdk = 34
        versionCode = 200
        versionName = "0.2.0"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile(rootProject.file("app/AndroidManifest.xml"))
            java.srcDirs(rootProject.file("src"))
            res.srcDirs(rootProject.file("app/res"))
            assets.srcDirs(rootProject.file("app/assets"))
            resources.srcDirs(rootProject.file("app/resources"))
        }
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
            // LSPosed loads GlassEntry by name from assets/xposed_init, so keep shrinking off.
            isMinifyEnabled = false
            isShrinkResources = false
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
    compileOnly(files(rootProject.file("libs/api-82.jar")))
}
