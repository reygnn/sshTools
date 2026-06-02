plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace  = "com.github.reygnn.core.data"
    compileSdk = 36
    defaultConfig {
        minSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric needs the merged manifest/resources
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core) // StateFlow/combine for serverSelectionState
    testImplementation(libs.junit)
    testImplementation(project(":core-testing"))
    // Robolectric: real Android runtime (DataStore, filesDir) for SettingsStore.
    testImplementation(libs.robolectric)
    // Instrumented (Tier 3): KeyVault against the real hardware-backed Keystore.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
