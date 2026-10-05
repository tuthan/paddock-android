import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.tuthan.paddock"
    buildFeatures { compose = true; buildConfig = true }
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

    // One code base, one application id and one release key on every route; the flavors differ only in what is added to it. The foss
    // build (GitHub, F-Droid, IzzyOnDroid) is the free version: no billing library and Pro capabilities locked, with no way to buy them
    // there (vault decision M4, 2026-10-04). Whoever wants every capability builds from source: -PpaddockUnlocked=true turns the foss
    // build's own switch on, and the release cut always passes -PpaddockUnlocked=false. The foss *debug* variant (the device harness, the
    // flows and instrumentation, none of it published) defaults to unlocked, so a flow written before Pro existed still reaches every
    // screen; -PpaddockUnlocked=false builds it locked to rehearse the gate (androidComponents below). The play build adds the Play
    // Billing library and is the only place Pro is sold (Phase 13).
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            val unlocked = providers.gradleProperty("paddockUnlocked").map { it.toBooleanStrict() }.orElse(false).get()
            buildConfigField("boolean", "UNLOCKED", unlocked.toString())
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "UNLOCKED", "false")
        }
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

    // No dependency-metadata block in the APK or bundle: it is encrypted to Google's key, so the bytes are neither
    // reviewable nor reproducible for a release cut, and they disclose the dependency list.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(17)
}

// The host scripts the app offers to install are the ones pinned in host/SOURCE.json. The build copies each script into
// assets and writes its pinned hash beside it; the app refuses to use a script whose hash is not that pin.
abstract class GenerateRelayAssets : DefaultTask() {
    @get:InputFiles abstract val scripts: ConfigurableFileCollection
    @get:InputFile abstract val source: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun generate() {
        val manifest = source.get().asFile.readText()
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        for (file in scripts.files.sortedBy { it.name }) {
            val name = file.name
            val pin = Regex("\"host/${Regex.escape(name)}\"\\s*:\\s*\"sha256:([0-9a-f]{64})\"").find(manifest)
                ?.groupValues?.get(1) ?: throw GradleException("host/SOURCE.json has no sha256 pin for host/$name")
            file.copyTo(File(out, name))
            // The first two scripts keep their original pin names (paddock-relay.sha256); every later file's pin is its full name plus .sha256.
            File(out, if (name == "paddock-relay.py" || name == "paddock-control.py") name.removeSuffix(".py") + ".sha256" else "$name.sha256").writeText(pin)
        }
    }
}

androidComponents {
    onVariants { variant ->
        // The property wins in every foss variant; without it only a foss release is locked (see the flavor comment above).
        if (variant.flavorName == "foss") {
            val unlocked = providers.gradleProperty("paddockUnlocked").map { it.toBooleanStrict() }.orElse(variant.buildType == "debug").get()
            variant.buildConfigFields?.put("UNLOCKED", com.android.build.api.variant.BuildConfigField("boolean", unlocked.toString(), "foss: -PpaddockUnlocked, else unlocked only in debug"))
        }
        val task = tasks.register<GenerateRelayAssets>("generate${variant.name.replaceFirstChar { it.uppercase() }}RelayAssets") {
            scripts.from(
                listOf("paddock-relay.py", "paddock-control.py", "paddock-alert-relay.py", "paddock-alert-relay.service", "alert-relay.example.toml", "paddock-decide.py", "paddock-claude-permission-hook.py")
                    .map { rootProject.layout.projectDirectory.file("host/$it") },
            )
            source.set(rootProject.layout.projectDirectory.file("host/SOURCE.json"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateRelayAssets::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.sshlib)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    // Play Billing and what it brings: the play flavor only. The foss flavor never sees them (tools/check-release-apk.py --flavor foss denies them).
    "playImplementation"(libs.play.billing)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Espresso 3.7.0 over the 3.5.0 that ui-test-junit4 brings: 3.5.0 calls InputManager.getInstance, removed in Android 17 (API 37).
    androidTestImplementation(libs.androidx.test.espresso.core)
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
    // STRICT: a configuration resolved without lock state fails instead of resolving unlocked.
    lockMode.set(LockMode.STRICT)
}
