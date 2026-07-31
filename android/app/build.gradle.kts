plugins {
    id("com.android.application")
}

android {
    namespace = "com.marathonrecomp.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marathonrecomp.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                // box64 is C++-aware in places and links the shared STL; "none" would
                // break its build, so the app uses the same STL it does.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // 16 KB page support: required for Android 15+ devices.
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    // -PbundleBox64=false builds the launcher without box64, which is a
                    // lot quicker when iterating on the UI.
                    "-DMARATHON_DROID_BUNDLE_BOX64=" +
                        if (project.findProperty("bundleBox64") == "false") "OFF" else "ON"
                )
                cFlags += listOf("-std=gnu11")
            }
        }

        ndk {
            // The whole point is running an x86_64 binary on ARM64 phones.
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // box64 is shipped as libbox64.so and has to end up on disk as a real,
            // executable file — Android only permits exec() from nativeLibraryDir, and
            // only when the libraries were extracted rather than loaded from the APK.
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Debug-signed so testers can install over an existing build; real
            // distribution re-signs anyway.
            signingConfig = signingConfigs.getByName("debug")
        }

        debug {
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "marathondroid-${buildType.name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.activity:activity:1.9.3")
}
