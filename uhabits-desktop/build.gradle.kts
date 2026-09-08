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
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":uhabits-core"))
    // JdbcDatabase ships in uhabits-core's jvmMain, but the driver it needs was
    // only ever pulled in for core's own tests. The desktop client is the first
    // thing to open a real database file outside Android, so it brings its own.
    implementation(libs.sqlite.jdbc)
    implementation(libs.jline)
    // JLine pulls in slf4j-api, and slf4j 1.x prints three lines of complaint
    // on every start when it finds no binding. Nothing here logs through it, so
    // it gets the no-op binding rather than an exclusion -- excluding the api
    // outright risks a NoClassDefFoundError from whichever JLine class touches
    // it, and this costs four kilobytes.
    runtimeOnly(libs.slf4j.nop)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.hamcrest)
}

application {
    applicationName = "uhabits"
    mainClass.set("org.isoron.uhabits.desktop.MainKt")
}

tasks.named<JavaExec>("run") {
    // A terminal UI needs the real console, not Gradle's captured stream.
    standardInput = System.`in`
}
