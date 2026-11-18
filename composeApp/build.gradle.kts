import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.*

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidxRoom)
}

kotlin {

    jvm("desktop")

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // The VRCX-0 core's static library, produced on macOS:
    //   cargo build --target aarch64-apple-ios --release -p vrcx-0-mobile-core --lib
    //   copy target/aarch64-apple-ios/release/libvrcx_0_mobile_core.a → src/nativeInterop/cinterop/vrcx_core.a
    // (see docs/FORK-DELTA.md §8). Its presence decides two things below: whether the cinterop is
    // registered at all, and which VrcxCoreBridge implementation is compiled.
    val vrcxCoreLib = layout.projectDirectory
        .file("src/nativeInterop/cinterop/vrcx_core.a").asFile
    val hasVrcxCore = vrcxCoreLib.exists()

    // Printed, not assumed: the CI asserts on this line, because a silently skipped cinterop would still
    // produce a linking framework - against the stub - and look like success.
    println(
        "VRCX core for iOS: " +
            if (hasVrcxCore) "iosCore (static library present)" else "iosStub (no static library)"
    )

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            // Dynamic, and that is load-bearing: the VRCX core ships as a Rust static library
            // (`libvrcx_0_mobile_core.a`). A *static* ComposeApp.framework does not absorb cinterop
            // static libraries - it leaves their symbols undefined until the final app link, which then
            // fails with `_vrcx_core_start` not found. Linking the framework dynamically puts the archive
            // into the framework binary, so the app links against a framework that already defines them.
            isStatic = false
        }

        if (hasVrcxCore) {
            iosTarget.compilations.getByName("main").cinterops.create("vrcxCore") {
                defFile(layout.projectDirectory.file("src/nativeInterop/cinterop/vrcx_core.def"))
                includeDirs(layout.projectDirectory.dir("src/nativeInterop/cinterop"))
                // Where `libvrcx_0_mobile_core.a` lives. It cannot be a `libraryPaths` entry in the .def:
                // that is resolved against cinterop's working directory, not the .def file, and failed with
                // "Could not find 'libvrcx_0_mobile_core.a' in neither of [.]". An absolute path from
                // Gradle is unambiguous.
                extraOpts(
                    "-libraryPath",
                    layout.projectDirectory.dir("src/nativeInterop/cinterop").asFile.absolutePath,
                )
            }
        }
    }

    sourceSets {
        // Exactly one of these provides `VrcxCoreBridge` for the Apple targets: the real implementation
        // calls into the static library through cinterop, the stub reports that the core is not built in.
        // Choosing by file presence keeps `compileKotlinIosArm64` working on machines without an iOS
        // toolchain (Windows), where the cinterop cannot exist at all.
        //
        // `matching {}.all {}` rather than `getByName`: `iosMain` is created by the default hierarchy
        // template after configuration, and it has to be the shared set (MainViewController lives there
        // and can only see declarations from its own set and its parents).
        matching { it.name == "iosMain" }.all {
            kotlin.srcDir(if (hasVrcxCore) "src/iosCore/kotlin" else "src/iosStub/kotlin")
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.zxing.embedded)
            implementation(libs.androidx.core)
            implementation(libs.androidx.exifinterface)
            implementation(libs.androidx.sqlite.framework)
            implementation(libs.awebp)

            implementation(libs.ktor.client.okhttp)
        }

        androidUnitTest.dependencies {
            implementation(libs.robolectric)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.androidx.sqlite.bundled)
        }

        val desktopMain by getting
        desktopMain.dependencies {
            // support Dispatchers.Main
            implementation(libs.kotlinx.coroutines.swing)

            implementation(compose.desktop.currentOs)

            implementation(libs.ktor.client.okhttp)
            implementation(libs.jna.platform)
            implementation(libs.androidx.sqlite.bundled)
        }

        val desktopTest by getting
        desktopTest.dependencies {
            implementation(compose.desktop.currentOs)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(libs.okio.fakefilesystem)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.okio)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.navigation3.ui)
            implementation(libs.navigationevent.compose)
            implementation(libs.material3.adaptive.navigation3)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
//            implementation(compose.components.uiToolingPreview)

            implementation(libs.multiplatform.settings)

            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization.kotlinx.json)

            implementation(libs.coil.compose.core)
            implementation(libs.coil.network.ktor)

            implementation(libs.chrisbanes.haze)

            implementation(libs.filekit.dialogs.compose)
            implementation(libs.qrose)

            implementation(libs.androidx.room.runtime)

//            implementation(libs.kamel)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.multiplatform.settings.test)
            // commonTest 直接使用 FakeFileSystem，Android 单元测试编译也需要该依赖。
            implementation(libs.okio.fakefilesystem)
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {

    applicationVariants.all {
        outputs.all {
            val variantName = rootProject.name
            val versionName = versionName
            val newApkName = "$variantName-v$versionName.apk"
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = newApkName
        }
    }
    namespace = "io.github.vrcmteam.vrcm"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    // Keep literal shortcut components: some OEM launchers do not resolve targetPackage resources.
    sourceSets["debug"].res.srcDir("src/androidDebug/res")

    defaultConfig {
        applicationId = libs.versions.app.packageName.get()
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.app.code.get().toInt()
        versionName = libs.versions.app.version.get()
    }

    // 分发渠道：github 是 GitHub Release 的 APK，应用内检查并下载安装新版本；
    // play 是上架 Google Play 的 AAB，按 Play 政策只能由 Play 更新，不带自更新代码和安装权限。
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
        }
        create("play") {
            dimension = "distribution"
        }
    }
    sourceSets["github"].manifest.srcFile("src/androidGithub/AndroidManifest.xml")
//    buildFeatures {
//        compose = true
//    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 防止没有local.properties文件没有配置签名报错导致没办法构建项目
    var storeFile: File? = null
    var storePass: String? = null
    var keyAlias: String? = null
    var keyPass: String? = null
    project.rootProject.file("local.properties").also {
        if (!it.isFile) return@also
        val properties = Properties()
        properties.load(it.inputStream())
        val storeFilePath = properties.getProperty("store_file")
         storeFile = if (storeFilePath.isNullOrEmpty()) null else rootProject.file(storeFilePath)
         storePass = properties.getProperty("store_pass")
         keyAlias = properties.getProperty("key_alias")
         keyPass = properties.getProperty("key_pass")
    }

    if (storeFile != null) {
        signingConfigs {
            create("release") {
                this.storeFile = storeFile
                this.storePassword = storePass
                this.keyAlias = keyAlias
                this.keyPassword = keyPass
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (storeFile != null) {
                this.signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("debug") {
            this.applicationIdSuffix = ".debug"
            this.isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

// 渠道专属依赖要等上面的 productFlavors 建好对应配置后再添加
dependencies {
    add("playImplementation", libs.play.app.update.ktx)
}


compose.desktop {
    application {
        mainClass = "io.github.vrcmteam.vrcm.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // The Windows DSL has no packageName override, so the global name produces VRCM.exe.
            // Keep the existing package identifier explicit for macOS and Linux below.
            packageName = libs.versions.app.name.get()
            packageVersion = libs.versions.app.version.get()

            macOS {
                packageName = libs.versions.app.packageName.get()
                iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
            }
            linux {
                packageName = libs.versions.app.packageName.get()
            }
            description = "VRChat friend and content manager"
            vendor = "VRCM Team"
            windows {
                iconFile.set(project.file("src/desktopMain/resources/VRCM.ico"))
                menuGroup = "VRCM"
                upgradeUuid = "aebfb803-0655-4c7e-8c79-f29e14618397"
            }
        }
    }
}
