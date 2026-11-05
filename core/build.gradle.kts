plugins {
    id("com.android.library")
}

android {
    namespace = "app.tvplay.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.bouncycastle)
    implementation(libs.jmdns)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
