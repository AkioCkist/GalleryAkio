buildscript {
    dependencies {
        // AGP 9 ships built-in Kotlin support; pin KGP so it matches the Compose compiler plugin.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
