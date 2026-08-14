import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val stockGuestAssetSource = rootProject.layout.projectDirectory.dir("guest/image/out")

/**
 * Verifies the described stock Linux guest and stages it under an `assets/guest/` root.
 *
 * The image describes itself, so this script no longer holds a list of its filenames.
 *
 * What it used to hold was four names in a fixed order, which had to stay in step by hand with the
 * same four names in RuntimeStorage. Reading `image.json` means adding a payload, or shipping a
 * different image entirely, is a change to the image build alone.
 */
abstract class StageGuestAssets : DefaultTask() {
    // The payload list is not known until the manifest is read, so the whole source directory is
    // the declared input. A file tree rather than `inputs.dir` because a fresh worktree has no
    // image at all — `guest/image/out/` is gitignored — and configuration must survive that; the
    // missing image is reported at execution time, where it can say what to do about it.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val guestImage: ConfigurableFileCollection

    /** Where [guestImage] was collected from, so a missing image can be named in the failure. */
    @get:Internal
    abstract val guestImageDirectory: DirectoryProperty

    /** Set by AGP through `addGeneratedSourceDirectory`; it owns the location. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val sourceDirectory = guestImageDirectory.get().asFile
        val manifestFile = sourceDirectory.resolve(MANIFEST_NAME)
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
        check(roles.toSet().containsAll(REQUIRED_ROLES)) {
            "Guest image $imageId is missing " +
                "${(REQUIRED_ROLES - roles.filterNotNull().toSet()).joinToString()}"
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
        val outputRoot = outputDirectory.get().asFile
        outputRoot.deleteRecursively()
        val guestDirectory = outputRoot.resolve("guest")
        check(guestDirectory.mkdirs()) { "Could not create ${guestDirectory.absolutePath}" }
        val staged = listOf(MANIFEST_NAME) + payloadNames.flatMap { listOf(it, "$it.sha256") }
        staged.forEach { assetName ->
            Files.createLink(
                guestDirectory.resolve(assetName).toPath(),
                sourceDirectory.resolve(assetName).toPath(),
            )
        }
        logger.lifecycle("Staged guest image $imageId@$imageVersion (${payloadNames.size} payloads)")
    }

    companion object {
        const val MANIFEST_NAME = "image.json"
        val REQUIRED_ROLES = setOf("kernel", "initrd", "system", "workspace")
    }
}

/**
 * Reads the packaged APK back and fails if the Linux it is supposed to carry is not in it.
 *
 * Staging the guest and packaging it are two separate steps, and for a while the second silently
 * did not happen: `assets.srcDir(<task>)` names a directory to AGP but not a producer, because the
 * asset merge reads the source set through `getSrcDirs()`, which flattens every entry to a bare
 * `java.io.File`. `assembleStockDebug` therefore packaged whatever the staging directory happened
 * to hold, which on a machine that had built once was the whole image and on a fresh checkout was
 * nothing at all — an 81 MB APK that installs, runs, and cannot start a VM.
 *
 * The wiring below is the fix. This task is the assertion that it stays fixed: it is the one check
 * that looks at the artifact a user actually installs rather than at the graph that produced it.
 */
abstract class VerifyBundledGuestImage : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val built = builtArtifactsLoader.get().load(apkDirectory.get())
            ?: error("No APK to check for a bundled guest image in ${apkDirectory.get().asFile}")

        val lines = built.elements.map { element ->
            val apk = File(element.outputFile)
            ZipFile(apk).use { zip ->
                val manifestPath = "assets/guest/${StageGuestAssets.MANIFEST_NAME}"
                val manifestEntry = zip.getEntry(manifestPath)
                    ?: error(
                        "${apk.name} carries no bundled Linux guest: $manifestPath is missing. " +
                            "The app installs and then reports \"No complete verified guest " +
                            "image is installed yet\", because RuntimeStorage.readBundledImage() " +
                            "has nothing to read. Build the guest image with " +
                            "./guest/build-container.sh and rebuild.",
                    )

                @Suppress("UNCHECKED_CAST")
                val manifest = zip.getInputStream(manifestEntry).use {
                    groovy.json.JsonSlurper().parse(it) as Map<String, Any?>
                }

                @Suppress("UNCHECKED_CAST")
                val payloads = manifest["payloads"] as? List<Map<String, Any?>>
                check(!payloads.isNullOrEmpty()) {
                    "${apk.name} bundles a $manifestPath that declares no payloads"
                }

                val required = payloads.flatMap { payload ->
                    val name = payload["file"] as? String
                    check(!name.isNullOrBlank()) {
                        "${apk.name} bundles a $manifestPath with an unnamed payload"
                    }
                    listOf("assets/guest/$name", "assets/guest/$name.sha256")
                }
                val missing = required.filter { zip.getEntry(it) == null }
                check(missing.isEmpty()) {
                    "${apk.name} carries an incomplete Linux guest: $manifestPath names " +
                        "${missing.joinToString()}, which the APK does not contain."
                }

                "${apk.name}: ${manifest["id"]}@${manifest["version"]}, ${payloads.size} payloads"
            }
        }

        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(lines.joinToString("\n", postfix = "\n"))
        lines.forEach { logger.lifecycle("Bundled guest image verified — $it") }
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
}

/**
 * Hands the staged guest to AGP as a *generated* asset directory, per stock variant.
 *
 * `sourceSets.named("stock") { assets.srcDir(prepareStockGuestAssets) }` looked like it did this
 * and did not: AGP resolves a source set through `getSrcDirs()`, which is
 * `project.files(entries).files` — every entry is flattened to a `java.io.File`, so the task
 * behind the directory is lost and no consumer ever learns to run it. The path survives, which is
 * what made the bug quiet: the merge happily packaged the directory when an earlier build had
 * left something in it, and packaged nothing at all when it had not.
 *
 * `addGeneratedSourceDirectory` is the wiring that carries a producer, so the asset merge now
 * waits for the staging task instead of reading whatever it finds. It also chooses the output
 * location, which is why the task no longer names one — and because the location is AGP's, the
 * lint models that read the variant's sources no longer read a directory behind Gradle's back.
 */
val prepareStockGuestAssets by tasks.registering {
    group = "build setup"
    description = "Stages the stock Linux guest for every stock variant."
}

androidComponents {
    onVariants(selector().withFlavor("runtime" to "stock")) { variant ->
        val suffix = variant.name.replaceFirstChar { it.titlecase() }

        val stage = tasks.register<StageGuestAssets>("prepare${suffix}GuestAssets") {
            group = "build setup"
            description =
                "Verifies and stages the described stock Linux guest as ${variant.name} assets."
            guestImage.from(project.fileTree(stockGuestAssetSource))
            guestImageDirectory.set(stockGuestAssetSource)
        }
        prepareStockGuestAssets.configure { dependsOn(stage) }
        variant.sources.assets
            ?.addGeneratedSourceDirectory(stage, StageGuestAssets::outputDirectory)

        val verify = tasks.register<VerifyBundledGuestImage>("verify${suffix}GuestImage") {
            group = "verification"
            description = "Fails if the ${variant.name} APK ships without its bundled Linux guest."
            // Taking the APK artifact as the input is also what orders this after packaging.
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            report.set(layout.buildDirectory.file("reports/guestImage/${variant.name}.txt"))
        }

        // `assemble` is the task tools/deploy.sh runs, so it is the one that has to refuse to
        // hand back an APK with no Linux in it. AGP has not created it yet at this point.
        afterEvaluate { tasks.named("assemble$suffix") { dependsOn(verify) } }
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
