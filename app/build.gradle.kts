plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.xraylocal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.xraylocal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
