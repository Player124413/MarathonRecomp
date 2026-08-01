plugins {
    id("com.android.application")
}

// Single source of truth for every Kotlin artifact in the graph (see the dependencies
// block for why this project, which has no Kotlin source, has to pin it at all).
val kotlinVersion = "1.9.24"

android {
    namespace = "com.marathonrecomp.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marathonrecomp.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Where the launcher fetches the prebuilt x86_64 rootfs from. Derived from the
        // GitHub repository the build ran in, so a fork automatically uses its own
        // release instead of the upstream one. -PsourceRepo=owner/name overrides it.
        val sourceRepo = (project.findProperty("sourceRepo") as String?)
            ?: System.getenv("GITHUB_REPOSITORY")
            ?: "Player124413/MarathonRecomp"

        buildConfigField("String", "SOURCE_REPOSITORY", "\"$sourceRepo\"")

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

    // ---------------------------------------------------------------------
    // Kotlin standard library alignment.
    //
    // This project has no Kotlin source at all, but AndroidX pulls kotlin-stdlib
    // in transitively. Kotlin 1.8.0 merged kotlin-stdlib-jdk7 and -jdk8 back into
    // kotlin-stdlib, so a graph holding kotlin-stdlib >= 1.8 together with an older
    // kotlin-stdlib-jdk7/jdk8 (1.6.21, dragged in by some other transitive) ends up
    // with the same classes twice, and dexing fails with:
    //
    //   Duplicate class kotlin.collections.jdk8.CollectionsJDK8Kt ...
    //
    // The BOM aligns every Kotlin artifact on one version, and the constraints raise
    // the jdk7/jdk8 artifacts to that version, where they are empty shims that simply
    // depend on kotlin-stdlib. Nothing is excluded, so any dependency that genuinely
    // needs them still resolves.
    // ---------------------------------------------------------------------
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:$kotlinVersion"))

    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion") {
            because("kotlin-stdlib-jdk7 was merged into kotlin-stdlib in Kotlin 1.8")
        }
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion") {
            because("kotlin-stdlib-jdk8 was merged into kotlin-stdlib in Kotlin 1.8")
        }
    }
}
