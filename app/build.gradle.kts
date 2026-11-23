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
        versionCode = 2200
        versionName = "0.2.2"
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
            // 剪掉没用到的代码/资源来压体积；类名不混淆（-dontobfuscate），
            // 保证 LSPosed 按 assets/xposed_init 里的全名加载入口类不出问题。
            isMinifyEnabled = true
            isShrinkResources = true
            // 必须显式声明，否则 R8 读不到 keep 规则，会把模块自己的类也裁光。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("app/proguard-rules.pro")
            )
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
    // 纯 Java Canvas 渲染：不把 Compose/Miuix 打进宿主进程。
    // 抖音自己带一份 androidx/Compose，模块再塞一份进去会在宿主里撞版本，
    // 表现就是「玻璃画不出来、什么都没变」。这条路走 p ure Android View + RenderEffect。
    compileOnly(files(rootProject.file("libs/api-82.jar")))
}
