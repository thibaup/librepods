import java.util.Properties

val appVersionName = "1.0.0-rc2"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.ksp)
    alias(libs.plugins.chaquopy)
//    alias(libs.plugins.hilt)
    id("kotlin-parcelize")
}

val localPropsFile = rootProject.file("local.properties")
val props = Properties().apply {
    if (localPropsFile.exists()) {
        load(localPropsFile.inputStream())
    }
}

val releaseSigningAvailable = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD"
).all { props[it]?.toString()?.isNotBlank() == true }

kotlin {
    compilerOptions {
        optIn.add(
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
        )
    }
}

android {
    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(props["RELEASE_STORE_FILE"] as String)
                storePassword = props["RELEASE_STORE_PASSWORD"] as String
                keyAlias = props["RELEASE_KEY_ALIAS"] as String
                keyPassword = props["RELEASE_KEY_PASSWORD"] as String
            }
        }
    }
    namespace = "me.kavishdevar.librepods"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.kavishdevar.librepods"
        minSdk = 33
        targetSdk = 37
        versionCode = 63
        versionName = appVersionName

        // Python 3.12 is available for Android's 64-bit ABIs. Restricting the native build
        // here also prevents packaging an APK whose Java code exists on 32-bit but whose
        // Find My network runtime cannot start.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            defaultConfig {
                minSdk = 33
            }
        }
        debug {
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            versionNameSuffix = "-debug"
            defaultConfig {
                minSdk = 33
            }
        }
    }
    productFlavors {
        create("foss") {
            dimension = "env"
            buildConfigField("Boolean", "PLAY_BUILD", "false")
        }
        create("coexist") {
            dimension = "env"
            applicationIdSuffix = ".hearttest"
            buildConfigField("Boolean", "PLAY_BUILD", "false")
        }
        create("play") {
            dimension = "env"
            buildConfigField("Boolean", "PLAY_BUILD", "true")
            versionNameSuffix = "-play"
            minSdk = 36
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        getByName("main") {
            res.directories += "src/main/res-apple"
        }
    }

    ndkVersion = "30.0.14904198"

    flavorDimensions += "env"
}

// FindMy.py depends on anisette, which declares Unicorn even though this port supplies
// Anisette with Apple's Android libraries. Unicorn has no Chaquopy wheel, so build a tiny
// auditable pure-Python compatibility wheel from the checked-in stub sources.
val unicornStubWheel = layout.buildDirectory.file(
    "generated/stub-wheels/unicorn-2.1.1-py3-none-any.whl"
)
val bleakStubWheel = layout.buildDirectory.file(
    "generated/stub-wheels/bleak-3.0.2-py3-none-any.whl"
)

fun resolvePythonExecutable(): String {
    providers.gradleProperty("pythonExecutable").orNull?.let { return it }

    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val names = if (isWindows) listOf("python.exe", "python3.exe") else listOf("python3", "python")
    val pathDirs = (System.getenv("PATH") ?: "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }

    for (name in names) {
        for (dir in pathDirs) {
            val candidate = File(dir, name)
            if (!candidate.isFile || !candidate.canExecute() || candidate.length() == 0L) continue
            if (candidate.absolutePath.contains("WindowsApps", ignoreCase = true)) continue
            return candidate.absolutePath
        }
    }

    throw GradleException(
        "No usable Python 3 interpreter found. Install Python 3 or pass " +
            "-PpythonExecutable=/path/to/python."
    )
}

val generateUnicornStubWheel by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the pure-Python Unicorn compatibility wheel for FindMy.py."

    val script = rootProject.file("scripts/build_unicorn_stub_wheel.py")
    inputs.dir(layout.projectDirectory.dir("stubs/unicorn"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(script).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(unicornStubWheel)
    outputs.cacheIf { true }

    commandLine(resolvePythonExecutable(), script.absolutePath, unicornStubWheel.get().asFile.absolutePath)
}

val generateBleakStubWheel by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the pure-Python Bleak compatibility wheel for Android."

    val script = rootProject.file("scripts/build_bleak_stub_wheel.py")
    inputs.dir(layout.projectDirectory.dir("stubs/bleak"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(script).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(bleakStubWheel)
    outputs.cacheIf { true }

    commandLine(resolvePythonExecutable(), script.absolutePath, bleakStubWheel.get().asFile.absolutePath)
}

tasks.matching { it.name.contains("PythonRequirements") || it.name.contains("PythonReqs") }
    .configureEach { dependsOn(generateUnicornStubWheel, generateBleakStubWheel) }

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install(unicornStubWheel.get().asFile.absolutePath)
            install(bleakStubWheel.get().asFile.absolutePath)
            install(
                "git+https://github.com/parawanderer/FindMy.py@" +
                    "23a9b8d7109b405f8362ea1e69ebe51f9ca82fca"
            )
            install("NSKeyedUnArchiver==1.5")
            install("PyYAML==6.0.3")
        }
    }
    productFlavors {}
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.annotations)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.billing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.aboutlibraries)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.backdrop)
//    implementation(libs.hilt)
//    implementation(libs.hilt.compiler)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigationevent)
    implementation(libs.zip4j)
    implementation(libs.osmdroid)
    testImplementation(libs.junit)
}

aboutLibraries {
    export {
        prettyPrint = true
        excludeFields = listOf("generated")
        outputFile = file("src/main/res/raw/aboutlibraries.json")
    }
}

val rootModuleDir = rootProject.file("../root-module-manual")
val releaseDir = rootProject.file("../release")

fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

fun registerRootModuleZipTask(
    name: String,
    flavor: String,
    buildType: String
) = tasks.register<Zip>(name) {

    val variantTask = "assemble${cap(flavor)}${cap(buildType)}"
    dependsOn(variantTask)

    val apkPath = "outputs/apk/$flavor/$buildType/app-$flavor-$buildType.apk"

    from(rootModuleDir)

    duplicatesStrategy = DuplicatesStrategy.WARN

    from(layout.buildDirectory.file(apkPath)) {
        into("system/priv-app/LibrePods")
        rename { "LibrePods.apk" }
    }

    delete(layout.buildDirectory.dir("outputs/rootModuleZips"))

    archiveFileName.set("LibrePods-FOSS-v$appVersionName-$buildType.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/rootModuleZips"))
}

val zipRelease = registerRootModuleZipTask(
    "zipReleaseModule",
    "foss",
    "release"
)

val zipDebug = registerRootModuleZipTask(
    "zipDebugModule",
    "foss",
    "debug"
)

val collect = tasks.register<Copy>("collectReleaseArtifacts") {

    dependsOn(
        zipRelease,
        zipDebug,
        "bundlePlayRelease"
    )

    into(releaseDir)

    from(layout.buildDirectory.dir("outputs/apk/foss/release")) {
        include("*.apk")
        rename(".*", "LibrePods-FOSS-v$appVersionName-release.apk")
    }

    from(layout.buildDirectory.dir("outputs/apk/foss/debug")) {
        include("*.apk")
        rename(".*", "LibrePods-FOSS-v$appVersionName-debug.apk")
    }

    from(layout.buildDirectory.dir("outputs/bundle/playRelease")) {
        include("*.aab")
    }

    from(layout.buildDirectory.dir("outputs/rootModuleZips")) {
        include("*.zip")
    }
}

tasks.register("packageReleaseArtifacts") {
    dependsOn(collect)
}
