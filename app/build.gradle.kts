plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull()
val ciVersionName = System.getenv("APP_VERSION_NAME")

android {
    namespace = "com.drchzy.offlinephoneagent"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.drchzy.offlinephoneagent"
        minSdk = 30
        targetSdk = 35
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
