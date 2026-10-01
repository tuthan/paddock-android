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
}

kotlin {
    jvmToolchain(17)
}

// The relay script the app offers to install on a host is the one pinned in host/SOURCE.json. The build copies the script
// into assets and writes the pinned hash beside it; the app refuses to use a script whose hash is not that pin.
abstract class GenerateRelayAssets : DefaultTask() {
    @get:InputFile abstract val relay: RegularFileProperty
    @get:InputFile abstract val source: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun generate() {
        val pin = Regex("\"host/paddock-relay.py\"\\s*:\\s*\"sha256:([0-9a-f]{64})\"").find(source.get().asFile.readText())
            ?.groupValues?.get(1) ?: throw GradleException("host/SOURCE.json has no sha256 pin for host/paddock-relay.py")
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        relay.get().asFile.copyTo(File(out, "paddock-relay.py"))
        File(out, "paddock-relay.sha256").writeText(pin)
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<GenerateRelayAssets>("generate${variant.name.replaceFirstChar { it.uppercase() }}RelayAssets") {
            relay.set(rootProject.layout.projectDirectory.file("host/paddock-relay.py"))
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
