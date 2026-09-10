import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 provides Kotlin compilation itself; applying org.jetbrains.kotlin.android on top
// of it is now an error rather than a redundancy.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Credentials live in keystore.properties (gitignored) or, failing that, in Gradle
// properties so CI can inject them without a file on disk.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String): String? =
    keystoreProperties.getProperty(name) ?: providers.gradleProperty(name).orNull

val releaseStore: String? = secret("NUMERA_STORE_FILE")

// Resolved against the repository root, which is where keystore.properties is read from
// and where the keystore lives. Project.file() inside this script resolves against app/.
val releaseKeystore: File? = releaseStore?.let { rootProject.file(it) }

/** Why a release build could not be signed, or null when it can be. */
val unsignableReason: String? = when {
    releaseKeystore == null -> "NUMERA_STORE_FILE is not set"
    !releaseKeystore.exists() -> "no keystore at ${releaseKeystore.absolutePath}"
    else -> null
}

// An unsigned release APK installs nowhere. Read off the requested task names rather than
// off the task graph: a graph listener cannot be stored in the configuration cache, and
// this has to stay quiet for a debug build on a machine that has never seen the keystore.
val releaseRequested = gradle.startParameter.taskNames.any { requested ->
    val task = requested.substringAfterLast(':')
    task == "build" || task == "assemble" ||
        (task.contains("Release") &&
            (task.startsWith("assemble") || task.startsWith("bundle") || task.startsWith("install")))
}
if (releaseRequested && unsignableReason != null) {
    throw GradleException(
        "Release build would be unsigned: $unsignableReason. See README.md for the signing properties."
    )
}

// The check above reads the task names exactly as they were typed, which is only a fast
// path: Gradle expands camelCase abbreviations *after* populating startParameter, so
// `./gradlew :app:bR` reaches bundleRelease having matched none of the prefixes above, and
// `packageRelease` is a real task name none of them cover either. Backstop it on the two
// tasks that actually write the artifact, so no spelling of the command can produce an
// unsigned APK or bundle. Only the String is captured, so the configuration cache is intact,
// and the check runs at execution time rather than poisoning `lint` or a debug build.
unsignableReason.let { reason ->
    tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }
        .configureEach {
            doFirst {
                if (reason != null) {
                    throw GradleException(
                        "Release build would be unsigned: $reason. " +
                            "See README.md for the signing properties."
                    )
                }
            }
        }
}

android {
    namespace = "app.numera.calculator"
    // Compiled against 37 because the current androidx libraries require it; the app still
    // *targets* 36, which is the Android 16 runtime behaviour the phone actually ships.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.numera.calculator"
        // Material You dynamic colour starts at 31, and this ships preloaded on Android 16.
        // Anything lower would be compat branches the target device never executes.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (unsignableReason == null) {
                storeFile = releaseKeystore
                storePassword = secret("NUMERA_STORE_PASSWORD")
                keyAlias = secret("NUMERA_KEY_ALIAS")
                keyPassword = secret("NUMERA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Assigned whether or not the credentials resolved. Left null, a missing keystore
            // is not an error at all — the build succeeds and hands over an unsigned artifact.
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The app is shipped preloaded, so a missing translation is a shipped defect rather
    // than something to notice in a report nobody reads. HardcodedText is deliberately not
    // listed: its detector only reads android:text/hint attributes in XML layouts and never
    // looks at a Kotlin `Text("...")` call, so in a Compose-only app it can never fire.
    // Listing it as fatal made the build look like it guarded against untranslated literals
    // when only review does.
    lint {
        fatal += listOf("MissingTranslation")
    }

    // Every locale ships inside the base APK instead of in a per-language split. Left at
    // bundletool's default, Play installs only the splits matching the device's *system*
    // languages, while res/xml/locales_config.xml offers all twelve to the Android 13+
    // per-app language picker — so picking a language whose split was never installed renders
    // the whole app in English with no error, no logcat line and nothing to click. The app
    // cannot repair that itself: fetching a split needs
    // com.google.android.play:feature-delivery, which is not androidx, and a preloaded or
    // offline install has no store round trip to fetch it in the first place. The whole
    // resource table is 460 KB uncompressed against a 3.3 MB APK, so the split bought nothing
    // worth this.
    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }
}

/**
 * Writes `res/xml/shortcuts.xml` for one variant from the template in `src/main/shortcuts/`.
 *
 * The launcher shortcuts name MainActivity explicitly, and an explicit component must carry
 * the package the build actually installs — the plain applicationId in release, the
 * ".debug"-suffixed one in debug. Nothing short of generating the file can do that: a
 * resource file cannot expand a manifest placeholder, and a `@string` reference in
 * `targetPackage` is resolved by the system's ShortcutParser against the *system's*
 * resources, so it stores the unresolved `@2131362132` as the package name and the shortcut
 * points at an app that does not exist. Two hand-written copies under `src/debug` and
 * `src/release` would work, and would drift the first time one was edited.
 */
abstract class GenerateShortcutsTask : DefaultTask() {
    @get:Input
    abstract val applicationId: Property<String>

    @get:InputFile
    abstract val template: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val xml = template.get().asFile.readText().replace("\${applicationId}", applicationId.get())
        val out = outputDir.get().asFile.resolve("xml/shortcuts.xml")
        out.parentFile.mkdirs()
        out.writeText(xml)
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val generate = tasks.register<GenerateShortcutsTask>("generate${name}Shortcuts") {
            applicationId.set(variant.applicationId)
            template.set(layout.projectDirectory.file("src/main/shortcuts/shortcuts.xml"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(generate, GenerateShortcutsTask::outputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":math"))
    implementation(project(":units"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
