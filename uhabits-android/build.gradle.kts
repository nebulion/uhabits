/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

plugins {
    alias(libs.plugins.agp)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint.plugin)
}

tasks.compileLint {
    dependsOn("updateTranslators")
}

/*
Added on top of kotlinOptions to work around this issue:
https://youtrack.jetbrains.com/issue/KTIJ-24311/task-current-target-is-17-and-kaptGenerateStubsProductionDebugKotlin-task-current-target-is-1.8-jvm-target-compatibility-should#focus=Comments-27-6798448.0-0
Updating gradle might fix this, so try again in the future to remove this and run:
./gradlew --rerun-tasks :uhabits-android:kaptGenerateStubsReleaseKotlin
If this doesn't produce any warning, try to remove it.
 */
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "org.isoron.uhabits"
    compileSdk = 36

    defaultConfig {
        // Upstream numbers its releases major*10000 + minor*100 + patch, so
        // 2.3.1 is 20301 and even a hypothetical 99.99.99 would only reach
        // 999999. F-Droid and Aurora Store are both installed on the Kompakt
        // and both decide "update available" by comparing their index's
        // versionCode against the installed one, so parking this fork above
        // everything upstream can ever publish means neither store will offer
        // to replace it. See docs/PROJECT-STATUS.md, "Keeping the stores off
        // this build", for the two defences behind this one.
        versionCode = 1000000
        versionName = "2.3.1-mmd"
        minSdk = 28
        targetSdk = 36
        applicationId = "org.isoron.uhabits"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (System.getenv("LOOP_KEY_ALIAS") != null) {
            create("release") {
                keyAlias = System.getenv("LOOP_KEY_ALIAS")
                keyPassword = System.getenv("LOOP_KEY_PASSWORD")
                storeFile = file(System.getenv("LOOP_KEY_STORE"))
                storePassword = System.getenv("LOOP_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.txt")
            // Falls back to the debug keystore, deliberately. This build is
            // sideloaded and never goes near a store, and that signature is
            // what keeps the stores out: F-Droid's and Aurora's copies of Loop
            // are signed with a different key, so Android refuses to install
            // one over this rather than silently replacing the owner's data.
            // It also lets release and debug builds replace each other, which
            // is what makes "release for daily use, debug for diagnostics"
            // practical. Set LOOP_KEY_ALIAS and friends to sign properly.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }

        debug {
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        targetCompatibility(JavaVersion.VERSION_17)
        sourceCompatibility(JavaVersion.VERSION_17)
    }

    kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()
    kotlinOptions.freeCompilerArgs +=
        listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    buildFeatures.viewBinding = true
    buildFeatures.compose = true
    lint.abortOnError = false
}

dependencies {
    compileOnly(libs.jsr250.api)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.jsr305)
    implementation(libs.dagger)
    implementation(libs.guava)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.jackson)
    implementation(libs.ktor.client.json)
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.appcompat)
    implementation(libs.legacy.preference.v14)
    implementation(libs.legacy.support.v4)
    implementation(libs.material)
    implementation(libs.documentfile)
    implementation(libs.opencsv)
    implementation(libs.konfetti.xml)
    implementation(project(":uhabits-core"))

    // Mudita Mindful Design: Mudita's own E Ink component library (Apache 2.0),
    // the same one their Kompakt apps are built with. Compose-only, which is
    // why Compose is enabled above.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.mmd)

    ksp(libs.dagger.compiler)

    androidTestImplementation(libs.bundles.androidTest)
    testImplementation(libs.bundles.test)
}
