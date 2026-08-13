import java.nio.file.Files
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val stockGuestAssetSource = rootProject.layout.projectDirectory.dir("guest/image/out")
val generatedStockAssets = layout.buildDirectory.dir("generated/stockGuestAssets")

/**
 * The image describes itself, so this script no longer holds a list of its filenames.
 *
 * What it used to hold was four names in a fixed order, which had to stay in step by hand with the
 * same four names in RuntimeStorage. Reading `image.json` means adding a payload, or shipping a
 * different image entirely, is a change to the image build alone.
 */
val stockGuestManifestName = "image.json"
val stockGuestRequiredRoles = setOf("kernel", "initrd", "system", "workspace")

val prepareStockGuestAssets by tasks.registering {
    group = "build setup"
    description = "Verifies and stages the described stock Linux guest as APK assets."
    // The payload list is not known until the manifest is read, so the whole output directory is
    // the declared input. A file tree rather than `inputs.dir` because a fresh worktree has no
    // image at all — `guest/image/out/` is gitignored — and configuration must survive that; the
    // missing image is reported at execution time, where it can say what to do about it.
    inputs.files(project.fileTree(stockGuestAssetSource))
        .withPropertyName("stockGuestImage")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedStockAssets)

    doLast {
        val sourceDirectory = stockGuestAssetSource.asFile
        val manifestFile = sourceDirectory.resolve(stockGuestManifestName)
        check(manifestFile.isFile) {
            "Missing stock guest payload: ${manifestFile.absolutePath}. " +
                "Build the guest image with ./guest/build-container.sh."
        }

        @Suppress("UNCHECKED_CAST")
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any?>
        val imageId = manifest["id"] as? String
        val imageVersion = manifest["version"] as? String
        check(!imageId.isNullOrBlank() && !imageVersion.isNullOrBlank()) {
            "${manifestFile.absolutePath} does not name an image id and version"
        }

        @Suppress("UNCHECKED_CAST")
        val payloads = manifest["payloads"] as? List<Map<String, Any?>>
        check(!payloads.isNullOrEmpty()) { "${manifestFile.absolutePath} declares no payloads" }

        val roles = payloads.map { it["role"] as? String }
        check(roles.toSet().containsAll(stockGuestRequiredRoles)) {
            "Guest image $imageId is missing " +
                "${(stockGuestRequiredRoles - roles.filterNotNull().toSet()).joinToString()}"
        }
        check(roles.size == roles.toSet().size) { "Guest image $imageId declares a role twice" }

        val payloadNames = payloads.map { payload ->
            val role = payload["role"] as? String
            val payloadName = payload["file"] as? String
            check(!payloadName.isNullOrBlank()) { "Guest image $imageId gives role $role no file" }
            check(!payloadName.contains('/') && payloadName != ".." ) {
                "Guest image $imageId gives role $role a path rather than a filename: $payloadName"
            }
            val declared = (payload["sha256"] as? String).orEmpty().lowercase()
            check(declared.matches(Regex("[0-9a-f]{64}"))) {
                "Guest image $imageId gives role $role a malformed SHA-256"
            }

            val file = sourceDirectory.resolve(payloadName)
            val checksumFile = sourceDirectory.resolve("$payloadName.sha256")
            check(file.isFile) { "Missing stock guest payload: ${file.absolutePath}" }
            check(checksumFile.isFile) { "Missing stock guest checksum: ${checksumFile.absolutePath}" }

            // The sibling `.sha256` files predate the manifest and are kept as an independent
            // witness: they are written by a separate step of the image build, so a manifest that
            // disagrees with them means the two halves of a build came from different images.
            val checksumFields = checksumFile.readText().trim().split(Regex("\\s+"), limit = 2)
            val expected = checksumFields.firstOrNull().orEmpty().lowercase()
            check(expected.matches(Regex("[0-9a-f]{64}"))) {
                "Malformed SHA-256 in ${checksumFile.absolutePath}"
            }
            check(checksumFields.getOrNull(1)?.removePrefix("*") == payloadName) {
                "Checksum manifest ${checksumFile.name} does not name $payloadName"
            }
            check(expected == declared) {
                "${manifestFile.name} and ${checksumFile.name} disagree about $payloadName"
            }

            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual == expected) {
                "SHA-256 mismatch for ${file.absolutePath}: expected $expected, found $actual"
            }
            payloadName
        }

        // The asset root needs a `guest/` prefix, but a second 490 MB image set would waste
        // developer disk space. Hard links give AAPT the required layout while retaining one
        // underlying copy of every tracked guest artifact.
        val outputDirectory = generatedStockAssets.get().asFile
        project.delete(outputDirectory)
        val guestDirectory = outputDirectory.resolve("guest")
        check(guestDirectory.mkdirs()) { "Could not create ${guestDirectory.absolutePath}" }
        val staged = listOf(stockGuestManifestName) +
            payloadNames.flatMap { listOf(it, "$it.sha256") }
        staged.forEach { assetName ->
            Files.createLink(
                guestDirectory.resolve(assetName).toPath(),
                sourceDirectory.resolve(assetName).toPath(),
            )
        }
        logger.lifecycle("Staged guest image $imageId@$imageVersion (${payloadNames.size} payloads)")
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

        // Box's GitHub App, which is how a box connects to GitHub.
        //
        // A client id is public by design — the device flow Box uses has no client secret, which is
        // precisely why it is the flow a phone app can run at all. It still comes from a property
        // rather than being written here, so a fork can point at its own app without editing code,
        // and so a build made without one is a *build* that says so rather than a flow that fails
        // at GitHub with a message about an unknown client. See docs/github-auth.md.
        buildConfigField(
            "String",
            "GITHUB_CLIENT_ID",
            "\"${(project.findProperty("box.github.clientId") as String?).orEmpty()}\"",
        )
        buildConfigField(
            "String",
            "GITHUB_APP_SLUG",
            "\"${(project.findProperty("box.github.appSlug") as String?) ?: "box"}\"",
        )
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
        // The producing task rather than its output directory: naming the task lets Gradle infer
        // the dependency for every consumer of this asset directory, which is more than the asset
        // merge. Lint builds a model of each source set too, and wiring only `mergeStock*Assets`
        // by hand left `generateStock*LintVitalReportModel` reading a directory it had no reason
        // to wait for — a validation failure in `./gradlew build`.
        assets.srcDir(prepareStockGuestAssets)
    }
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
