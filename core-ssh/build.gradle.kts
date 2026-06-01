plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace  = "com.github.reygnn.core.ssh"
    compileSdk = 36
    defaultConfig { minSdk = 36 }
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
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.junit)
    testImplementation(project(":core-testing"))
}
