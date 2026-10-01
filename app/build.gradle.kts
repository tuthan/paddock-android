plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.tuthan.paddock"
    buildFeatures { compose = true }
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.tuthan.paddock"
        minSdk = 26
        // The recorded target is 37, so ACCESS_LOCAL_NETWORK is declared; see docs/build-decision-record.md.
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // -PminifiedTest=true runs the debug variant through R8 so instrumentation exercises the shrunk graph.
        if (providers.gradleProperty("minifiedTest").isPresent) {
            debug {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-test.pro")
                testProguardFiles("proguard-test.pro")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        // sshlib's runtime dependencies carry duplicate licence text.
        resources.pickFirsts += listOf("META-INF/LICENSE.md", "META-INF/LICENSE", "META-INF/NOTICE.md", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.sshlib)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// sshlib's ML-KEM implementation (kyber, keccak, kotlincrypto) is excluded; the cost is recorded in docs/ssh-library-decision.md.
// tink stays: sshlib needs it for X25519 and Ed25519.
configurations.configureEach {
    exclude(group = "asia.hombre")
    exclude(group = "org.kotlincrypto")
    exclude(group = "org.kotlincrypto.random")
}

dependencyLocking {
    lockAllConfigurations()
}
