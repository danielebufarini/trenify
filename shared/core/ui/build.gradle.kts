import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    android {
        namespace = "it.danielebufarini.trenify.core.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions.jvmTarget = JvmTarget.JVM_11
        androidResources.enable = true
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core:model"))
            implementation(project(":shared:core:domain"))
            implementation(libs.decompose)
            api(libs.kotlinx.coroutines.core)
            // T8.15: compose.runtime is the only Compose UI artifact retained
            // in shared code. No @Composable remains in this module; the
            // runtime is required on the classpath by the Compose compiler
            // plugin (build-verified) and backs the retained Compose
            // Resources string catalog (Res) consumed by notification
            // localizers and resource mappings.
            api(compose.runtime)
            api(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "it.danielebufarini.trenify.core.ui.resources"
}
