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
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only for ImageToolbox's GPUImage build, and pinned and checksummed (gradle/verification-metadata.xml).
        maven("https://jitpack.io") {
            content { includeGroup("com.github.T8RIN.ImageToolboxLibs") }
        }
    }
}

rootProject.name = "GalleryAkio"
include(":app")
include(":editor")
include(":lib:cropper")
include(":lib:gesture")
