plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace  = "com.github.reygnn.core.ui"
    compileSdk = 36
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric needs resources/manifest
    }
}

dependencies {
    implementation(project(":core-ssh")) // LogLine, rendered by the shared LogLineRow

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    // Compose UI tests under Robolectric (no device needed).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.ui.test.manifest) // empty activity for createComposeRule
}
