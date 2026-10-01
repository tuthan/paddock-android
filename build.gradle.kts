buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    // STRICT: a locked configuration without lock state fails instead of resolving unlocked.
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
