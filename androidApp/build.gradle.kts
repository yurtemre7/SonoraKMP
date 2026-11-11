import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
}

// Release signing: keystore.properties locally, env vars in CI. Falls back to debug signing.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use(::load)
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val sonoraVersionName: String = providers.gradleProperty("sonora.versionName").get()

/**
 * versionCode derived from versionName (override with -Psonora.versionCode=N):
 *   (major * 1_000_000 + minor * 1_000 + patch) * 10 + stage
 * stage = 9 for a final release; pre-releases sort below it:
 *   alpha.N -> 0..2, beta.N -> 3..5, rc.N -> 6..8 (N clamped, N=1 is the first slot).
 * e.g. 1.2.3 -> 10020039, 1.2.3-beta.1 -> 10020033, 1.2.3-rc.2 -> 10020037.
 */
fun deriveVersionCode(name: String): Int {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+.*)?$""").matchEntire(name)
        ?: error("sonora.versionName '$name' is not semantic (X.Y.Z[-pre])")
    val (major, minor, patch) = match.destructured.toList().take(3).map(String::toInt)
    require(minor < 1_000 && patch < 1_000) { "minor/patch must be < 1000: $name" }
    val pre = match.groupValues[4]
    val stage = if (pre.isEmpty()) 9 else {
        val label = pre.substringBefore('.').lowercase()
        // Long: nightly identifiers are 12-digit timestamps (YYYYMMDDHHMM).
        val n = pre.substringAfter('.', "1").toLongOrNull() ?: 1L
        val base = when (label) { "beta" -> 3; "rc" -> 6; else -> 0 }
        base + (n - 1).coerceIn(0L, 2L).toInt()
    }
    return (major * 1_000_000 + minor * 1_000 + patch) * 10 + stage
}

val sonoraVersionCode: Int = providers.gradleProperty("sonora.versionCode").orNull?.toInt()
    ?: deriveVersionCode(sonoraVersionName)

android {
    namespace = "de.yurtemre.sonora"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.yurtemre.sonorakmp"
        minSdk = 24
        targetSdk = 37
        versionCode = sonoraVersionCode
        versionName = sonoraVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GITHUB_REPO", "\"yurtemre7/SonoraKMP\"")
        manifestPlaceholders["appName"] = "@string/app_name"
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "ja")
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "SONORA_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "SONORA_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SONORA_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SONORA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appName"] = "SonoraKMP Dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        disable += listOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.media3.common.util.UnstableApi",
        )
    }
}

/** P8-04: bundles CHANGELOG.md and the OFL font licence as generated assets (UI-36/37). Configuration-cache safe. */
abstract class BundleDocsAssetsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val docs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        docs.files.filter { it.isFile }.forEach { it.copyTo(java.io.File(out, it.name), overwrite = true) }
    }
}

val bundleDocsAssets = tasks.register<BundleDocsAssetsTask>("bundleDocsAssets") {
    docs.from(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    docs.from(rootProject.layout.projectDirectory.file("docs/licenses/OFL-BricolageGrotesque.txt"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleDocsAssets, BundleDocsAssetsTask::outputDir)
    }
}

dependencies {
    implementation(project(":shared"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowsize)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.appcompat)
    implementation(libs.palette)
    implementation(libs.documentfile)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose.viewmodel)

    implementation(libs.coil.compose)
    implementation(libs.materialkolor)
    implementation(libs.reorderable)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.aboutlibraries.compose.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.sqlite.framework)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
