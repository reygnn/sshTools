plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace  = "com.github.reygnn.core.ssh"
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
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
            )
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.junit)
    testImplementation(project(":core-testing"))
    // In-process SSH server for the integration tests (real handshake/auth/exec).
    testImplementation(libs.sshd.core)
    testImplementation(libs.eddsa) // MINA's server-side ssh-ed25519 signature factory
    // Instrumented (Tier 3): BcOpenSshKeyProvider against Android's Conscrypt.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    testRuntimeOnly(libs.slf4j.nop) // silence MINA's slf4j "no binding" warning in tests
}
