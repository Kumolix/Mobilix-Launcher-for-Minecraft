plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.mobilix.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.mobilix.launcher"
        minSdk = 28
        targetSdk = 34
        versionCode = 64
        versionName = rootProject.file("VERSION").readText().trim()
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("mobilix.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = "mobilix"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // распаковка .tar.xz для Java-рантайма
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
