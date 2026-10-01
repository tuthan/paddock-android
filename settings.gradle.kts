pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "paddock-android"

include(":core", ":app")

// Phase 02 throwaway spike; deleted in the SSH library decision commit.
include(":spike-sshj", ":spike-sshlib")
project(":spike-sshj").projectDir = file("spike/sshj")
project(":spike-sshlib").projectDir = file("spike/sshlib")
