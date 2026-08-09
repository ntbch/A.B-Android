plugins {
    id("com.android.application")
}

android {
    namespace = "com.ab.assistant"

    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.ab.assistant"

        minSdk = 26
        targetSdk = 37

        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // This APK targets the on-device MNN model on the project's arm64 POCO test device.
    // The native model runtime is not packaged for ChromeOS/x86_64.
    lint {
        disable += "ChromeOsAbiSupport"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
