plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.tuthan.paddock.spike.sshlib"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.tuthan.paddock.spike.sshlib"
        minSdk = 26
        // Target 36 keeps the spike independent of the Android 17 local-network grant (Phase 02 slice 8).
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.0-spike"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Run the suite against the minified build: `-PspikeTest=r8test`. Debug-signed so it installs.
    testBuildType = (project.findProperty("spikeTest") as String?) ?: "debug"

    buildTypes {
        create("r8test") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles("proguard-r8test.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Every BouncyCastle jar and several others carry the same licence text; keep one copy.
        resources.pickFirsts += listOf("META-INF/LICENSE.md", "META-INF/LICENSE", "META-INF/NOTICE.md", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }

    sourceSets {
        getByName("main").kotlin.directories.add(rootProject.file("spike/common/main/kotlin").path)
        getByName("androidTest").kotlin.directories.add(rootProject.file("spike/common/androidTest/kotlin").path)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.sshlib)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

dependencyLocking {
    lockAllConfigurations()
}
