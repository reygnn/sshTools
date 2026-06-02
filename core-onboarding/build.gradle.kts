plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace  = "com.github.reygnn.core.onboarding"
    compileSdk = 36
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core-ssh"))
    implementation(project(":core-data"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(project(":core-testing"))
}
