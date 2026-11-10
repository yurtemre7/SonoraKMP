plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "de.yurtemre.sonora.shared"
        compileSdk = 37
        minSdk = 24
        withHostTestBuilder {}.configure {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.datastore.preferences.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.datastore.preferences)
            implementation(libs.jaudiotagger)
            implementation(libs.documentfile)
            implementation(libs.palette)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.turbine)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room.compiler)
}

// Host (JVM) tests run Room with the bundled SQLite driver. The Android artifact has no
// desktop native library, so extract the one for this machine from sqlite-bundled-jvm.
val hostSqliteNatives: Configuration by configurations.creating { isTransitive = false }
dependencies { hostSqliteNatives(libs.sqlite.bundled.jvm) }

val hostSqlitePlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm") }
    when {
        os.contains("mac") -> if (arm) "osx_arm64" else "osx_x64"
        os.contains("win") -> "windows_x64"
        else -> if (arm) "linux_arm64" else "linux_x64"
    }
}
val hostSqliteDir = layout.buildDirectory.dir("hostSqliteNatives")

val extractHostSqliteNatives by tasks.registering(Sync::class) {
    from(zipTree(hostSqliteNatives.singleFile)) {
        include("natives/$hostSqlitePlatform/*")
    }
    into(hostSqliteDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(extractHostSqliteNatives)
    systemProperty(
        "java.library.path",
        hostSqliteDir.get().asFile.resolve("natives/$hostSqlitePlatform").absolutePath,
    )
}
