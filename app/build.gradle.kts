import java.nio.file.Files
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val stockGuestAssetSource = rootProject.layout.projectDirectory.dir("guest/image/out")
val generatedStockAssets = layout.buildDirectory.dir("generated/stockGuestAssets")
val stockGuestPayloads = listOf(
    "base-system.qcow2",
    "workspace.qcow2",
    "kernel",
    "initrd.img",
)
val stockGuestAssetFiles = stockGuestPayloads.flatMap { listOf(it, "$it.sha256") }

val prepareStockGuestAssets by tasks.registering {
    group = "build setup"
    description = "Verifies and stages the stock Linux guest as APK assets."
    inputs.files(stockGuestAssetFiles.map(stockGuestAssetSource::file))
        .withPropertyName("stockGuestAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedStockAssets)

    doLast {
        val sourceDirectory = stockGuestAssetSource.asFile
        stockGuestPayloads.forEach { payloadName ->
            val payload = sourceDirectory.resolve(payloadName)
            val checksumFile = sourceDirectory.resolve("$payloadName.sha256")
            check(payload.isFile) { "Missing stock guest payload: ${payload.absolutePath}" }
            check(checksumFile.isFile) { "Missing stock guest checksum: ${checksumFile.absolutePath}" }

            val checksumFields = checksumFile.readText().trim().split(Regex("\\s+"), limit = 2)
            val expected = checksumFields.firstOrNull().orEmpty().lowercase()
            check(expected.matches(Regex("[0-9a-f]{64}"))) {
                "Malformed SHA-256 in ${checksumFile.absolutePath}"
            }
            check(checksumFields.getOrNull(1)?.removePrefix("*") == payloadName) {
                "Checksum manifest ${checksumFile.name} does not name $payloadName"
            }

            val digest = MessageDigest.getInstance("SHA-256")
            payload.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual == expected) {
                "SHA-256 mismatch for ${payload.absolutePath}: expected $expected, found $actual"
            }
        }

        // The asset root needs a `guest/` prefix, but a second 490 MB image set would waste
        // developer disk space. Hard links give AAPT the required layout while retaining one
        // underlying copy of every tracked guest artifact.
        val outputDirectory = generatedStockAssets.get().asFile
        project.delete(outputDirectory)
        val guestDirectory = outputDirectory.resolve("guest")
        check(guestDirectory.mkdirs()) { "Could not create ${guestDirectory.absolutePath}" }
        stockGuestAssetFiles.forEach { assetName ->
            Files.createLink(
                guestDirectory.resolve(assetName).toPath(),
                sourceDirectory.resolve(assetName).toPath(),
            )
        }
    }
}

android {
    namespace = "dev.localagent.workstation"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.localagent.workstation"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { buildConfig = true }
    androidResources {
        // These artifacts are copied into app-private storage before QEMU boots them. Keeping
        // them stored avoids a costly second compression pass and permits efficient asset reads.
        noCompress += listOf("qcow2", "img", "kernel")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    flavorDimensions += "runtime"
    productFlavors {
        create("stock") {
            dimension = "runtime"
            applicationIdSuffix = ".stock"
            versionNameSuffix = "-stock"
        }
        create("avf") {
            dimension = "runtime"
            applicationIdSuffix = ".avf"
            versionNameSuffix = "-avf-experimental"
        }
    }

    sourceSets.named("stock") {
        assets.srcDir(generatedStockAssets)
    }
}

tasks.matching { it.name.startsWith("mergeStock") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareStockGuestAssets)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":runtime-api"))
    implementation(project(":runtime-qemu"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // A real org.json, because the stub in the mockable android.jar throws from every method.
    testImplementation(libs.json)
}
