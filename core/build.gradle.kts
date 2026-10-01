plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

dependencyLocking {
    lockAllConfigurations()
    // STRICT: a configuration resolved without lock state fails instead of resolving unlocked.
    lockMode.set(LockMode.STRICT)
}

tasks.test {
    // The only herdr input at build time is the pinned corpus inside this repository.
    systemProperty("paddock.repoRoot", rootProject.projectDir.absolutePath)
    inputs.dir(rootProject.file("protocol"))
    inputs.dir(rootProject.file("fixtures"))
    inputs.dir(rootProject.file("host"))
    // Integration tests read the disposable session from the environment; a change must rerun them.
    inputs.property("paddockTestSocket", providers.environmentVariable("PADDOCK_TEST_SOCKET").orElse(""))
}

// :core is the JVM decision layer. It must not see Android APIs or depend on another project.
val coreSources = fileTree("src") { include("**/*.kt") }
val forbiddenImport = Regex("""^\s*import\s+(android|androidx|com\.android)\.""")

val verifyCoreBoundary = tasks.register("verifyCoreBoundary") {
    group = "verification"
    description = "Fails if :core imports Android APIs."
    inputs.files(coreSources)
    doLast {
        val violations = coreSources.files.flatMap { file ->
            file.readLines().withIndex()
                .filter { forbiddenImport.containsMatchIn(it.value) }
                .map { "${file.relativeTo(projectDir)}:${it.index + 1}: ${it.value.trim()}" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Android imports in :core:\n" + violations.joinToString("\n"))
        }
    }
}

val projectDependencies = configurations.flatMap { configuration ->
    configuration.dependencies.withType<ProjectDependency>().map { "${configuration.name} -> ${it.path}" }
}
check(projectDependencies.isEmpty()) { ":core must not depend on other projects: $projectDependencies" }

tasks.named("check") {
    dependsOn(verifyCoreBoundary)
}
