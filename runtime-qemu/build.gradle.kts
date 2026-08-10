plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.localagent.runtime.qemu"
    compileSdk = 35

    buildFeatures { aidl = true }

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
            }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":runtime-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // The agentd framing and multiplexer carry no Android dependency precisely so they can be
    // covered here, on the JVM, without a device or a booted guest.
    testImplementation(libs.junit)
    // A real org.json, because the stub in the mockable android.jar throws from every method.
    testImplementation(libs.json)
}
