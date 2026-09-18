import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    // T8.15: the Compose plugins stay ONLY as resource plumbing. No @Composable
    // remains in this module; the plugins aggregate the shared core:ui Res
    // catalog next to iOS test binaries (ResourceNotificationLocalizerTest
    // resolves real resources on simulator). No Compose UI artifact beyond
    // compose.runtime (compiler-plugin classpath requirement) is declared.
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.skie)
}

kotlin {
    android {
        namespace = "it.danielebufarini.trenify.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions.jvmTarget = JvmTarget.JVM_11
        androidResources.enable = true
        withHostTest {}
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        // The ordering end-to-end tests drive a real NativeSqliteDriver in
        // the test binary (same as :shared:data).
        iosTarget.binaries.all { linkerOpts("-lsqlite3") }
        iosTarget.binaries.framework {
            baseName = "SharedApp"
            isStatic = true
            binaryOption("bundleId", "it.danielebufarini.trenify.shared")
        }
    }

    sourceSets {
        // Explicit local XCTest fixture build only. Never included in a normal framework/app.
        if (providers.gradleProperty("trenify.nativeInteropTests").orNull == "true") {
            iosMain {
                kotlin.srcDir("src/nativeInteropTestSupport/kotlin")
                dependencies {
                    implementation(project(":shared:core:testing"))
                    implementation(libs.ktor.client.mock)
                    implementation(libs.sqldelight.native.driver)
                }
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(project(":shared:core:domain"))
            implementation(project(":shared:core:provider-api"))
            implementation(project(":shared:core:database"))
            implementation(project(":shared:core:network"))
            // api: androidApp/iOS hosts receive NotificationDestination
            // values from the tap bridges and pass them back into the
            // shared root entry point (T7.13-F/G).
            api(project(":shared:core:platform"))
            implementation(project(":shared:core:ui"))
            implementation(project(":shared:data"))
            implementation(project(":shared:provider:viaggiatreno"))
            implementation(project(":shared:provider:journey"))
            implementation(project(":shared:provider:mit-strikes"))
            implementation(project(":shared:feature:home"))
            implementation(project(":shared:feature:journey"))
            implementation(project(":shared:feature:station"))
            implementation(project(":shared:feature:train"))
            implementation(project(":shared:feature:monitoring"))
            implementation(project(":shared:feature:strikes"))
            implementation(project(":shared:feature:favorites"))
            implementation(project(":shared:feature:settings"))
            implementation(libs.decompose)
            // Resource-plumbing only (see plugins block): backs the retained
            // core:ui Res catalog for iOS test binaries. No UI code uses it.
            implementation(compose.runtime)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":shared:core:testing"))
            implementation(libs.kotlin.test)
        }
        named("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        iosTest.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}
