import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val appVersion = providers.gradleProperty("typebit.version").get()
val versionParts = appVersion.split('.').map(String::toInt)
require(versionParts.size == 3 && versionParts.all { it in 0..999 })
val appVersionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
val versionSource = layout.buildDirectory.dir("generated/typebitVersion")
val cargoLock = rootProject.layout.projectDirectory.file("native/Cargo.lock")
val generateVersion by tasks.registering {
    inputs.property("appVersion", appVersion)
    inputs.file(cargoLock)
    outputs.dir(versionSource)
    doLast {
        val engineVersion = Regex("(?m)^name = \"typebit\"\\s+version = \"([^\"]+)\"")
            .find(cargoLock.asFile.readText())?.groupValues?.get(1) ?: error("Missing typebit in Cargo.lock")
        val output = versionSource.get().file("com/typebit/app/BuildVersion.kt").asFile
        output.parentFile.mkdirs()
        output.writeText("""
            package com.typebit.app
            internal const val APP_VERSION = "$appVersion"
            internal const val ENGINE_VERSION = "$engineVersion"
        """.trimIndent())
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    sourceSets {
        // `by getting` / `by creating` are deprecated since Gradle 9.6.
        val commonMain = getByName("commonMain") { kotlin.srcDir(generateVersion) }
        val androidMain = getByName("androidMain")
        val desktopMain = getByName("desktopMain")
        val jvmShared = create("jvmShared") { dependsOn(commonMain) }
        val commonTest = getByName("commonTest")

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(compose.preview)
        }
        androidMain.dependsOn(jvmShared)

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        getByName("desktopTest").dependencies { implementation(compose.desktop.uiTestJUnit4) }
        desktopMain.dependsOn(jvmShared)
    }
}

android {
    namespace = "com.typebit.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.typebit.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersion
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.typebit.MainKt"
        nativeDistributions {
            modules("jdk.httpserver")
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "TypeBitTorrent"
            packageVersion = appVersion
            description = "TypeBitTorrent - a cross-platform BitTorrent client on the TypeBit Rust engine"
            vendor = "blueokanna"

            windows {
                menuGroup = "TypeBit"
                upgradeUuid = "7B5C9D1E-3A84-4F2B-9E10-2C6A8D4B1F37"
            }

        }
    }
}
