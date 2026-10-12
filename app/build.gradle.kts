plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionName: String = run {
    val prop = findProperty("versionName") as? String
    if (!prop.isNullOrBlank()) return@run prop.removePrefix("v")

    val envRef = System.getenv("GITHUB_REF_NAME")
    if (!envRef.isNullOrBlank() && envRef.startsWith("v")) {
        return@run envRef.removePrefix("v")
    }

    val envVersion = System.getenv("APP_VERSION_NAME")
    if (!envVersion.isNullOrBlank()) {
        return@run envVersion.removePrefix("v")
    }

    val gitTag = try {
        providers.exec {
            commandLine("git", "describe", "--tags", "--abbrev=0")
        }.standardOutput.asText.map { it.trim().removePrefix("v") }.orNull
    } catch (_: Throwable) {
        null
    }
    if (!gitTag.isNullOrBlank()) return@run gitTag

    "1.2.2"
}

val appVersionCode: Int = run {
    val prop = (findProperty("versionCode") as? String)?.toIntOrNull()
    if (prop != null) return@run prop

    val envCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull()
    if (envCode != null) return@run envCode

    val commitCount = try {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.map { it.trim().toIntOrNull() }.orNull
    } catch (_: Throwable) {
        null
    }
    1000 + (commitCount ?: 10)
}

android {
    namespace = "com.ray.pixelbandinfo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ray.pixelbandinfo.beta"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                val debugConfig = getByName("debug")
                storeFile = debugConfig.storeFile
                storePassword = debugConfig.storePassword
                keyAlias = debugConfig.keyAlias
                keyPassword = debugConfig.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += "BlockedPrivateApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // HiddenApiBypass
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.24.0")
}
