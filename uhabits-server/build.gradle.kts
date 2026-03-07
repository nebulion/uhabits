/*
 * Copyright (C) 2016-2021 Álinson Santos Xavier <git@axavier.org>
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
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":uhabits-core"))
    implementation(libs.ktor2.server.netty)
    implementation(libs.ktor2.server.core)
    implementation(libs.ktor2.server.content.negotiation)
    implementation(libs.ktor2.server.call.logging)
    implementation(libs.ktor2.server.status.pages)
    implementation(libs.ktor2.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.commons.lang3)
    implementation(libs.opencsv)
    implementation(libs.guava)
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveBaseName = "loop-habit-tracker-server"
    manifest {
        attributes["Main-Class"] = "org.isoron.uhabits.server.MainKt"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
