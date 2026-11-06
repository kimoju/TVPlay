plugins {
    alias(libs.plugins.android.application)
}

val customAuthenticationAssets = (
    providers.environmentVariable("TVPLAY_AUTH_ASSETS_DIR").orNull
        ?: providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR").orNull
    )?.let { file(it).canonicalFile }
    ?.takeIf { it.isDirectory }

val customKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    ?.let { file(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "app.tvplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.tvplay"
        minSdk = 24
        targetSdk = 37
        versionCode = 50
        versionName = "1.2.5"
    }

    customAuthenticationAssets?.let { sourceSets.getByName("main").assets.directories.add(it.path) }

    lint {
        disable += setOf("MissingTranslation", "StringFormatMatches")
    }

    signingConfigs {
        if (customKeystorePath != null) {
            create("release") {
                storeFile = customKeystorePath
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}

val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Verify that the bundled offline MFi authentication assets are present."
    val defaultAssetsDir = layout.projectDirectory.dir("src/main/assets").asFile
    val assetsRoot = customAuthenticationAssets ?: defaultAssetsDir
    doLast {
        check(listOf("identity.pk8", "certificate.p7b").all {
            assetsRoot.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Offline MFi authentication files (identity.pk8, certificate.p7b) are missing or empty in ${assetsRoot.resolve("offline-mfi")}" }
    }
}
tasks.named("preBuild") { dependsOn(verifyStandaloneAuthentication) }

tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone debug TVPlay APK with bundled offline MFi authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}

tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "Build a standalone release TVPlay APK with bundled offline MFi authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleRelease")
}
