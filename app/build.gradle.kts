import java.io.File

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
