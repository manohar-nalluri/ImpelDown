import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.impel.touchlock"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.impel.touchlock"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // No dependencies, no test runners, no instrumentation: keeps the APK minimal.
    }

    signingConfigs {
        // A project-local debug keystore keeps builds hermetic (CI images / sandboxes where
        // $HOME/.android is not writable). When the file is absent, AGP falls back to the
        // standard ~/.android/debug.keystore, which it creates on demand.
        val localDebugKeystore = rootProject.file("keystore/debug.keystore")
        if (localDebugKeystore.exists()) {
            getByName("debug") {
                storeFile = localDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

        // Real (self-signed) release certificate, read from the git-ignored keystore.properties.
        // Signing release builds with a proper certificate instead of the Android Debug one is
        // what keeps sideloading reliable on real phones: Play Protect and several OEM installers
        // treat debug-signed APKs as untrusted and simply report "App not installed".
        val keystorePropertiesFile = rootProject.file("keystore.properties")
        val keystoreProperties = Properties().apply {
            if (keystorePropertiesFile.exists()) {
                keystorePropertiesFile.inputStream().use { load(it) }
            }
        }
        val releaseStore = keystoreProperties.getProperty("storeFile")
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // All three schemes: v1 is what older installers/ROMs check, v2 is required from
                // Android 7 and v3 enables future key rotation. Costs a few KB, removes a whole
                // class of "App not installed" failures.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Uses the release certificate from keystore.properties. If that file is missing the
            // build still works, falling back to the debug key (installable, but flagged as
            // untrusted by Play Protect on many phones).
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    // The app deliberately uses only the Kotlin stdlib already required by the language.
    sourceSets.all {
        languageSettings.optIn("kotlin.RequiresOptIn")
    }
}

dependencies {
    // Intentionally empty: no AndroidX, no Material, no third-party libraries.
}
