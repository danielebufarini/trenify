import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    androidTestImplementation(project(":shared:core:platform"))
    androidTestImplementation(project(":shared:core:testing"))
    androidTestImplementation(project(":shared:core:ui"))
    androidTestImplementation(project(":shared:feature:home"))
    androidTestImplementation(project(":shared:feature:journey"))
    androidTestImplementation(project(":shared:feature:favorites"))
    androidTestImplementation(project(":shared:feature:monitoring"))
    androidTestImplementation(project(":shared:feature:settings"))
    androidTestImplementation(project(":shared:feature:station"))
    androidTestImplementation(project(":shared:feature:strikes"))
    androidTestImplementation(project(":shared:feature:train"))
    androidTestImplementation(libs.decompose)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.test.manifest)
    // AGP aligns instrumentation dependencies to the tested debug app's runtime.
    debugImplementation(libs.androidx.concurrent.futures)
    implementation(project(":shared:app"))
    implementation(project(":shared:feature:train"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(project(":shared:feature:home"))
    implementation(project(":shared:feature:journey"))
    implementation(project(":shared:feature:settings"))
    implementation(project(":shared:core:domain"))
    implementation(project(":shared:core:model"))
    implementation(project(":shared:core:ui"))
    implementation(project(":shared:core:network"))
    implementation(libs.decompose)
    implementation(libs.decompose.extensions.compose)

    implementation(libs.androidx.activity.compose)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.components.uiToolingPreview)
    testImplementation(libs.kotlin.test.junit)
    debugImplementation(compose.uiTooling)
}

android {
    namespace = "it.danielebufarini.trenify"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "it.danielebufarini.trenify"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets.getByName("androidTest").kotlin.srcDir("../shared/app/src/uiTest/kotlin")
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
