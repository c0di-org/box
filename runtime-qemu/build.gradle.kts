plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.localagent.runtime.qemu"
    compileSdk = 35

    buildFeatures { aidl = true }

    // Pinned rather than left to AGP's default, which is a version CI would have to download
    // mid-build. This one is installed on the GitHub runner image and is the newer of the two a
    // developer here already has, so both build the JNI shim with the same compiler.
    ndkVersion = "28.2.13676358"

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
