plugins {
    id("com.android.application")
}

val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")

android {
    namespace = "one.asmr"
    compileSdk = 35

    defaultConfig {
        applicationId = "one.asmr"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("releaseEnv") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystorePath != null) {
                signingConfigs.getByName("releaseEnv")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(files("libs/libv2ray-dial.aar"))
    implementation("androidx.core:core:1.15.0")
}
